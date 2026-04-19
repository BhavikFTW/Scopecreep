"""Chat orchestrator for Scopecreep.

Uses the OpenAI SDK directly. System prompt is composed at turn time from
every published profile in the memory layer plus a stable hardware-API
reference (the agent backend on :8000 exposes the instrument REST surface).

Keeps orchestration deliberately simple — no tool-call loop. The model is
instructed to respond in plain prose and embed executable Python in fenced
```python blocks. The IDE's Chat tab surfaces Run buttons on those blocks,
which POST back to /exec/python.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from memory import ProfileStore


_CODE_BLOCK_RE = re.compile(
    r"```(?:python|py)(?:[ \t]+path=([^\s`]+))?\s*\n(.*?)```", re.DOTALL
)


SYSTEM_BASE = """\
You are Scopecreep, an AI assistant embedded inside a JetBrains IDE. The user
is at a lab bench with real hardware connected via USB — an Analog Discovery
Rev C (scope + AWG + PSU + DIO) and optionally a DPS-150 bench supply. Your
job is to help them measure and test circuits by generating Python code that
drives instruments through the local hardware backend.

## How code is executed and saved

You operate DIRECTLY inside the user's open JetBrains project. Every
```python fenced block you emit becomes a card in the chat UI with three
buttons: Run (executes in the sidecar venv), Save (writes to the project),
Copy.

To persist a file in the user's project, annotate the fence with a project-
relative path:

    ```python path=instruments/scope_test.py
    # ... contents ...
    ```

When you want to build out real project structure — instrument controllers,
experiment scripts, result renderers, plot helpers — emit one `path=`-
annotated block per file. Prefer small, focused modules over one mega-file.
Suggested layout (adapt to what the user asks for):

    instruments/       # driver wrappers around the HTTP backend
    experiments/       # runnable test scripts
    results/           # generated data + plots land here at runtime
    display/           # plotting / dashboard helpers

If a block has no path= annotation, it's a throwaway scratch run — the user
will click Run and inspect output inline. No filesystem side effects.

The runtime env has `requests` and `numpy` available. The hardware backend
listens on http://127.0.0.1:8000 and the generated code MUST talk to it via
`requests.post(...)` / `requests.get(...)`. DO NOT import hardware drivers
directly.

## Hardware REST surface (on http://127.0.0.1:8000)

- POST /connect       body: {"bitstream_path": "<path>", "url": null}
- POST /disconnect
- GET  /status
- POST /scope/configure  body: {"ch":1, "range_v":5.0, "num_samples":1024, "sample_rate":1_000_000}
- POST /scope/capture    body: {"ch":1, "n_samples":1024}  returns {"samples":[...]}
- POST /awg/set          body: {"ch":1, "waveform":[...], "sample_rate":1_000_000}
- POST /awg/enable       body: {"ch":1, "enabled":true}
- POST /awg/amplitude    body: {"ch":1, "amplitude":1.0, "offset":0.0}
- POST /psu/set          body: {"ch":1, "voltage":3.3}
- POST /psu/enable       body: {"ch":1, "enabled":true}
- POST /dio/direction    body: {"pin_mask":0xFF, "output_mask":0x0F}
- POST /dio/write        body: {"pin_mask":0x0F, "value":0x0A}
- GET  /dio/read

Call pattern you should use in generated code:

```python
import requests
API = "http://127.0.0.1:8000"
r = requests.post(f"{API}/psu/set", json={"ch":1, "voltage":3.3})
r.raise_for_status()
```

## Operating rules

1. Narrate what you intend to do, THEN ask for confirmation ("ok to proceed?")
   before emitting code that energizes hardware (anything calling /psu/* or
   /awg/enable with enabled=true). On the user's next "go"/"yes"/"ok",
   emit the code.
2. Stay inside these clamps: PSU voltage ≤ 5.0 V, current ≤ 1.0 A. Refuse
   if the user asks for more.
3. Wrap hardware code in try/finally so PSU rails get disabled on error:
   ```python
   try:
       ...
   finally:
       requests.post(f"{API}/psu/enable", json={"ch":1,"enabled":False})
   ```
4. Return ONE self-contained ```python block per runnable step. Short prose
   between blocks is fine; don't dump multiple alternative implementations.
5. If the backend isn't connected (e.g. /status returns 503), the first thing
   the user should run is /connect with the bitstream path they were given
   at setup. Tell them.
"""


@dataclass
class CodeBlock:
    path: str | None
    code: str


@dataclass
class TurnResult:
    assistant_message: dict
    code_blocks: list[CodeBlock]


class ChatOrchestrator:
    def __init__(self, openai_client, store: ProfileStore | None, model: str):
        self.client = openai_client
        self.store = store
        self.model = model

    def _profiles_section(self) -> str:
        if self.store is None:
            return "(memory layer not configured — no instrument profiles available)"
        try:
            profiles = self.store.list_published(limit=20)
        except Exception as exc:  # noqa: BLE001
            return f"(failed to load profiles: {exc})"
        if not profiles:
            return "(no published instrument profiles yet)"
        parts = []
        for p in profiles:
            parts.append(f"### {p.title} (slug: {p.slug})\n\n{p.content}\n")
        return "\n---\n".join(parts)

    def build_system_prompt(self) -> str:
        return (
            SYSTEM_BASE
            + "\n\n## Instruments available (from memory layer)\n\n"
            + self._profiles_section()
        )

    def run_turn(self, messages: list[dict[str, Any]]) -> TurnResult:
        chat_messages = [{"role": "system", "content": self.build_system_prompt()}]
        for m in messages:
            role = m.get("role", "user")
            content = m.get("content", "")
            if role not in ("user", "assistant", "system"):
                role = "user"
            chat_messages.append({"role": role, "content": content})

        # Some newer models (gpt-5*, o1/o3, etc.) only accept the default
        # temperature, so we omit the param unless the model is known to
        # support it. Safer than feature-detecting per error.
        kwargs: dict = {"model": self.model, "messages": chat_messages}
        if self.model.startswith(("gpt-4", "gpt-3")):
            kwargs["temperature"] = 0.2
        resp = self.client.chat.completions.create(**kwargs)
        text = resp.choices[0].message.content or ""
        blocks: list[CodeBlock] = []
        for m in _CODE_BLOCK_RE.finditer(text):
            path = m.group(1)
            blocks.append(CodeBlock(path=path or None, code=m.group(2).strip()))
        return TurnResult(
            assistant_message={"role": "assistant", "content": text},
            code_blocks=blocks,
        )
