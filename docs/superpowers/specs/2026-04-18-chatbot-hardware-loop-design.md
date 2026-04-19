# Chatbot ↔ Hardware Loop — Design Spec & Continuation Plan

**Status:** planning for execution
**Author:** Bhavik + Claude, session 2026-04-18 (continuation of data flywheel work)
**Depends on:** `main` (contains data flywheel from PR #1, merge `7e2bbd3`)

This is the planning document to finish Scopecreep for the hackathon demo.
Meant to be self-contained — a teammate picking this up cold should be able to
execute without re-reading the chat log.

---

## Table of contents

1. Where we are now
2. Target demo (the 4-minute story)
3. What's missing, prioritized
4. Target architecture
5. Implementation blocks (G → M)
6. Benchy patterns we're copying
7. Risks & mitigations
8. Day-of-demo checklist
9. File manifest when complete

---

## 1. Where we are now

### Already on `main` (data flywheel, merged in PR #1)

- Supabase-backed instrument profile memory layer (`profiles` table + RLS scaffolding)
- Hand-written Analog Discovery Rev C profile as seed
- Sidecar `/memory/{recall,search,remember,publish,research}` endpoints
- Nebius-backed research flow (Gemma-3-27b default, ~$0.01/call)
- Profiles tab in the IDE tool window with Markdown preview
- `CodexProviderManager` for routing Codex CLI between OpenAI and Nebius
- Settings panel: Supabase / Nebius / OpenAI / codex provider fields

See `DATA_FLYWHEEL_CHANGES.md` for full file inventory.

### Validated, on other branches (teammate work — awaiting merge)

**jbhack repo** (parent directory, separate git repo):
- `python/drivers/analog_discovery/` — complete pyftdi-based AD driver:
  oscilloscope (`oscilloscope.py`), AWG (`awg.py`), PSU (`power.py`),
  DIO (`digital.py`), transport/JTAG/PTI/bitstream scaffolding. Full unit
  tests via mocks.
- `python/api/server.py` — FastAPI with **14 REST endpoints** already wrapping
  this driver (connect, disconnect, status, scope configure/capture, awg
  set/enable/amplitude, psu set/enable, dio direction/write/read). **This
  is exactly the surface Scopecreep's sidecar needs.**
- `backend/drivers/dps150.py` + `backend/api/routes/psu.py` — DPS-150
  programmable supply driver + FastAPI routes. Not strictly needed for the
  AD-only demo but available.

**Roman's branch** (`Roman_Schematic_Parser_UI`):
- `/upload` endpoint in sidecar for schematic + PCB file uploads
- `RunnerClient.uploadFiles(schematic, pcb)` in Kotlin
- SchDoc parser at `/home/alex/jbhack/python/schdoc/`
- `com.scopecreep.service.OpenAiClient.kt` — Kotlin OpenAI client for test-plan generation
- `com.scopecreep.service.GenerateOrchestrator.kt` — orchestration glue

### Locally stashed on this machine

`git stash list` on `dataflywheel` contains: `WIP schematic tab
(pre-dataflywheel-merge)` — partially-implemented `SchematicPanel.kt`,
`SchdocParserRunner.kt`, and tool-window wiring. Drop-in or discard at merge
time.

---

## 2. Target demo (the 4-minute story)

1. **Setup** (0:00–0:30): IDE is open. Scopecreep tool window on the right.
   Analog Discovery Rev C is physically wired to a trivial DUT (a resistor, an
   LED, whatever — bench-ready). Sidebar shows Ping / Profiles / **Chat**.
2. **Ground the agent** (0:30–0:50): Click the **Chat** tab. Type:
   > "I want to measure the voltage across R1 while I sweep the PSU from 0 to 3.3V."
3. **Agent narrates + asks for confirmation** (0:50–1:10): Agent responds:
   > "I'll configure PSU V+ rail, sweep 0→3.3V in 0.1V steps, capture scope CH1 after each step, and return the V(R1) vs V_PSU curve. Safety clamps: max 3.5V, max 100mA. Confirm before I energize."
4. **User confirms** (1:10–1:15): "go"
5. **Code generation** (1:15–1:50): Agent generates Python using the AD Rev C
   profile's API surface. Code appears in a syntax-highlighted code block.
   "Run" button below it.
6. **Execution** (1:50–2:30): User clicks Run. Sidecar executes the code
   against the real AD. Stdout streams back into the chat. Scope PNG renders
   inline.
7. **Iterate** (2:30–3:10): User says "now do the same but with a triangle wave
   on AWG CH1 at 1 kHz." Agent builds on context, generates the next code
   block, user runs it.
8. **Flywheel reveal** (3:10–3:50): Switch to Profiles tab → click **Research
   new instrument…** → type `Keithley 2400 SMU` → 15 seconds later a new
   profile appears (seeded via Nebius Qwen3-Coder or Gemma-3). Narrate:
   *"the plugin got smarter just now — every future session knows about the
   2400."*
9. **Close** (3:50–4:00): Pitch: "scoped MCP-style tools, SQL-native memory,
   Nebius-hosted inference, IDE-native. The flywheel makes every new
   instrument cheaper to onboard."

---

## 3. What's missing, prioritized

### P0 — demo-critical

1. **Chat tab in the tool window** with message history + input + send button
2. **Sidecar `/chat/turn` endpoint** that runs the OpenAI tool-call loop
3. **Tool definitions** exposing the AD driver to the agent (scope, awg, psu, dio)
4. **System prompt composer** that loads all published profiles + tool specs
5. **Generated-code execution path** — agent returns Python, user clicks Run,
   sidecar executes in venv, stdout/stderr stream back
6. **Port jbhack AD driver + endpoints into Scopecreep sidecar** (or proxy to
   the existing jbhack FastAPI server on a different port)

### P1 — demo polish

7. **Schematic upload button in chat** — reuses Roman's `/upload`, threads file
   path into the next turn's user message so the agent can reference it
8. **Confirmation gate** in the system prompt forcing the agent to narrate
   actions and wait for user ack before energizing hardware (benchy pattern)
9. **Inline scope-capture PNG rendering** in chat (sidecar returns chart URL,
   chat panel displays image)
10. **Profile list auto-refresh** after Research completes (Realtime
    subscription — currently user has to hit Refresh)

### P2 — post-hackathon

11. Re-enable RLS and fix the anon-insert policy
12. Publish button for draft profiles (currently auto-publish workaround)
13. JCEF-based Markdown preview (ditch the 1998 Swing HTML)
14. MCP server wrapping the memory layer
15. AD2/AD3 support
16. Clerk auth integration

### Completed in this session but **not** demo-critical
- Data flywheel infrastructure (lives independently, just doesn't *demo*
  without the chatbot riding on it)

---

## 4. Target architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ JetBrains IDE                                                   │
│                                                                 │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │
│  │ Ping tab     │ │ Profiles tab │ │ Chat tab     │ ← NEW      │
│  │ (exists)     │ │ (exists)     │ │ - message log│            │
│  │              │ │              │ │ - input field│            │
│  │              │ │              │ │ - Send btn   │            │
│  │              │ │              │ │ - 📎 schematic│           │
│  │              │ │              │ │ - Run btn on │            │
│  │              │ │              │ │   code blocks│            │
│  └──────────────┘ └──────────────┘ └──────────────┘            │
│                                           │                     │
└───────────────────────────────────────────┼─────────────────────┘
                                            │
                                            │ HTTP (sidecar)
                                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ Scopecreep sidecar (uvicorn on :8420)                           │
│                                                                 │
│  ┌──────────────────┐    ┌────────────────────────────────┐    │
│  │ /health, /upload │    │ /memory/* (existing)           │    │
│  │ (existing)       │    └────────────────────────────────┘    │
│  │                  │                                          │
│  └──────────────────┘    ┌────────────────────────────────┐    │
│  ┌──────────────────┐    │ /ad/* (NEW — ported from       │    │
│  │ /chat/turn (NEW) │───▶│   jbhack/python/api/server.py) │    │
│  │ - tool-call loop │    │   scope / awg / psu / dio      │    │
│  │ - OpenAI SDK     │    └────────────────────────────────┘    │
│  └──────────────────┘         │                                │
│          │                    ▼                                │
│          │              ┌──────────────────┐                   │
│          │              │ AnalogDiscovery  │                   │
│          │              │ driver (pyftdi)  │                   │
│          │              │ from jbhack      │                   │
│          │              └──────────────────┘                   │
│          ▼                                                     │
│  ┌──────────────────────────────────────────────────┐          │
│  │ /exec/python (NEW) — runs user-approved code in  │          │
│  │ the sidecar venv, captures stdout/stderr/plots   │          │
│  └──────────────────────────────────────────────────┘          │
│                                                                 │
└────────────────────┬────────────────────────────────────────────┘
                     │ USB
                     ▼
              Physical AD Rev C → DUT
```

**Key design decisions (locked in):**

- **Chat lives in the IDE tool window**, not a separate frontend. Plays into the "IDE-native" pitch.
- **OpenAI SDK in the sidecar**, not the plugin — the `openai` Python lib is already a dep, and the sidecar has the AD driver handles to pass to tool calls. Plugin just forwards messages over HTTP.
- **Tool-call orchestration on the sidecar**, not client-side. Each user message → `POST /chat/turn` → sidecar runs the OpenAI function-call loop (message → tool call → execute tool → message → ...) until OpenAI returns a final text response or a code block.
- **Code execution is a separate endpoint** (`/exec/python`). Agent returning Python in a code block is non-binding; user must explicitly press Run to execute. This is the UX gate; in the system prompt we ALSO tell the agent to narrate + wait for "go".
- **One global AD handle per sidecar process**, opened lazily on first hardware tool call. Benchy pattern.
- **Safety clamps as Python constants in the driver module**, not config. Hardcoded `PSU_MAX_VOLTAGE = 5.5`, `PSU_MAX_CURRENT = 1.0`. Exceeding → HTTP 400 before touching the device. Benchy pattern.
- **Generated code runs in the sidecar venv**, not a fresh subprocess — so it has access to the already-connected AD handle via a helper module `scopecreep_session` (exposes `ad`, `scope`, `awg`, `psu`, `dio` at module level).

---

## 5. Implementation blocks

Blocks continue from A–F (which were the data flywheel). Dependencies are
strict where indicated.

### Block G — Port AD driver + API endpoints into Scopecreep sidecar

**Owner:** whoever can touch Python fastest
**Effort:** 4 hrs
**Depends on:** nothing (teammate's driver is validated)

**Goal:** Scopecreep sidecar speaks to real AD hardware via a clean REST surface.

**Approach:** Two options, pick the simpler:

- **(G.i, recommended)** *Copy the jbhack driver into Scopecreep's sidecar*:
  vendor `python/drivers/analog_discovery/` under
  `src/main/resources/sidecar/analog_discovery/`. Extract bitstream file(s)
  to `~/.scopecreep/bitstream/` on first run. Port the 14 endpoints from
  `python/api/server.py` into `src/main/resources/sidecar/worker.py` (or a
  new `ad_routes.py`). This is the "one sidecar, one process" model.

- **(G.ii)** *Proxy to the jbhack FastAPI server* running on :8421. Simpler
  if the teammate keeps iterating on the jbhack side independently. Downside:
  two processes, two venvs, more demo-day fragility.

Ship (G.i).

**Tasks:**

1. Copy `python/drivers/analog_discovery/` → `src/main/resources/sidecar/analog_discovery/`
2. Copy bitstream from `python/drivers/analog_discovery/bitstream/` → `src/main/resources/sidecar/bitstream/`. Update driver to look up via relative path or env var.
3. Add to `requirements.txt`: `pyftdi>=0.55.0`, `numpy>=1.26`, `matplotlib>=3.8` (for PNG rendering of captures).
4. Update `SidecarManager.extractResources()` to also extract the `analog_discovery/` tree + bitstream.
5. Port the endpoint bodies from `python/api/server.py` into a new module `src/main/resources/sidecar/ad_routes.py`; mount under `worker.py` with `app.include_router(ad_router, prefix="/ad")`.
6. Add startup hook that optionally auto-connects at sidecar launch (env var `SCOPECREEP_AD_AUTOCONNECT=1`).
7. Add hardcoded safety clamps as module-level constants: `PSU_MAX_VOLTAGE = 5.5`, `PSU_MAX_CURRENT = 1.0`, `SCOPE_MAX_SAMPLES = 16384`.
8. Unit tests — port the mocks from jbhack, ensure endpoints return sane shapes.

**Acceptance:** `curl -X POST http://127.0.0.1:8420/ad/connect` returns success; subsequent `curl http://127.0.0.1:8420/ad/status` returns `{"connected": true, ...}`.

---

### Block H — Chat tab (Kotlin UI)

**Owner:** Kotlin-leaning teammate
**Effort:** 3 hrs
**Depends on:** nothing (pure UI)

**Deliverables:**

- `src/main/kotlin/com/scopecreep/ui/ChatPanel.kt` — new tab with:
  - Scrolling `JEditorPane` message log (HTML, one message per turn, alternating speaker)
  - Multi-line `JTextArea` input at the bottom
  - Send button (also fires on Cmd/Ctrl+Enter)
  - Paper-clip button to attach a schematic (fires file chooser, stores path for next turn)
  - Each code block in an agent message gets rendered inside a `<pre><code>` AND is followed by a visible **Run** button. Clicking Run hits `/exec/python` and streams output into the chat below the code.
- Message model: `data class ChatMessage(role: String, content: String, attachments: List<String>, codeBlocks: List<CodeBlock>)`.
- Panel state: persistent chat history for the IDE session only (no long-term persistence for MVP).
- Register the panel as a third tab in `ScopecreepToolWindowFactory`.

**Acceptance:** Tab renders, messages echo locally (without backend). Running the IDE doesn't crash when the tab is opened before the sidecar has AD configured.

---

### Block I — System prompt composer

**Owner:** Python side
**Effort:** 1 hr
**Depends on:** Block G (needs tool schemas), data flywheel (needs profile fetch)

**Deliverables:**

- `src/main/resources/sidecar/prompt.py` — single function `build_system_prompt(profile_store: ProfileStore) -> str`:
  1. Base system instructions (below, fixed string)
  2. Fetched instrument profiles (`SELECT content FROM profiles WHERE status='published' ORDER BY kind, slug`)
  3. Tool definitions inline (also sent as OpenAI function definitions — inline text is for context, function defs are for call routing)

**Base system prompt** (load-bearing parts):

```
You are Scopecreep, an AI assistant embedded inside a JetBrains IDE. The user
is at a lab bench with real hardware connected. Your job is to help them
measure and test circuits by generating Python code that drives their
instruments through the scopecreep_session helper module.

## Operating rules

1. NEVER energize hardware without narrating what you intend and waiting for
   the user to say "go", "yes", "ok", "proceed", or similar. If the user just
   says "do it", narrate first, then do it in the next turn.
2. Always stay inside the safety clamps: PSU voltages ≤ 5.5 V, currents ≤
   1.0 A, scope inputs ≤ ±25 V with 10× probe. If the user asks for something
   outside these, refuse and explain.
3. Python you generate runs in the sidecar venv with a pre-initialized
   AnalogDiscovery instance exposed as `ad`, `ad.scope`, `ad.awg`, `ad.psu`,
   `ad.dio`. NEVER call `ad.connect()` or `ad.disconnect()` in your code —
   the sidecar manages lifecycle.
4. Always wrap instrument code in try/finally so PSU rails disable even on
   error.
5. Return Python in a ```python fenced code block. The user must explicitly
   click Run; you CANNOT execute code yourself.
6. If the user attaches a schematic, read it via the upload reference ID and
   use its parsed netlist in your reasoning.

## Instruments available

{INJECTED: content of each published profile in the memory layer, concat'd}

## Tools

{INJECTED: summary of every tool the user's session exposes to you}
```

**Acceptance:** Unit test: `build_system_prompt(store)` returns a string >2 KB containing substring `"Analog Discovery"`.

---

### Block J — Tool-call loop (sidecar `/chat/turn`)

**Owner:** Python side
**Effort:** 3 hrs
**Depends on:** Blocks G, I

**Deliverables:**

- `src/main/resources/sidecar/chat.py` — `ChatOrchestrator` class:
  - `__init__(openai_client, profile_store, ad_driver, model="gpt-4o")`
  - `async run_turn(messages: list[Message], attachments: list[str]) -> TurnResult`
  - Uses OpenAI's [function calling](https://platform.openai.com/docs/guides/function-calling). Tools defined: `scope_capture`, `awg_set_waveform`, `psu_set_voltage`, `psu_enable`, `dio_write`, `dio_read`, `ad_status`. Each maps to a sidecar endpoint under `/ad/*`.
  - Loop: send messages → if OpenAI returns a tool call, execute it against the AD driver (reusing the `/ad/*` handlers' logic via direct import, not via HTTP self-call), append tool result → repeat up to 8 iterations, then force a text response.
  - Model: **`gpt-4o`** by default (good tool-calling fidelity), configurable via settings.
  - Cost: ~$0.02 per turn with gpt-4o. Cheaper alternative: `gpt-4o-mini` at ~$0.002/turn, but tool-calling reliability is noticeably worse.

- `/chat/turn` FastAPI endpoint in `worker.py` — request body:
  ```json
  { "messages": [{"role":"user","content":"..."}], "attachments": [] }
  ```
  Response:
  ```json
  { "messages": [...updated with assistant turn...], "tool_calls": [...], "code_blocks": ["..."] }
  ```

**Acceptance:** Integration test (with mocked OpenAI): send a user message that should trigger a `scope_capture` tool call; verify the orchestrator invokes the AD driver's `scope.capture(...)` and returns the response in the next assistant message.

---

### Block K — Generated-code execution

**Owner:** Python side
**Effort:** 2 hrs
**Depends on:** Block G

**Deliverables:**

- `src/main/resources/sidecar/exec.py`:
  ```python
  def run_user_code(code: str, session_globals: dict) -> ExecResult:
      stdout = io.StringIO()
      stderr = io.StringIO()
      try:
          with redirect_stdout(stdout), redirect_stderr(stderr):
              exec(compile(code, "<chat>", "exec"), session_globals)
          return ExecResult(ok=True, stdout=stdout.getvalue(), stderr=stderr.getvalue())
      except Exception as e:
          return ExecResult(ok=False, stdout=stdout.getvalue(),
                            stderr=stderr.getvalue() + traceback.format_exc())
  ```
- `src/main/resources/sidecar/session.py` — defines `session_globals` with:
  ```python
  session_globals = {"ad": ad_driver, "scope": ad_driver.scope,
                     "awg": ad_driver.awg, "psu": ad_driver.psu,
                     "dio": ad_driver.dio, "plt": matplotlib.pyplot,
                     "np": numpy, "print": print}
  ```
- `/exec/python` FastAPI endpoint: body `{"code": "..."}`, response `{"ok": bool, "stdout": str, "stderr": str, "plots": ["/artifacts/..."]}`.

**Security note:** running arbitrary Python in the sidecar venv is dangerous
for a production product. For a hackathon on a private network with known
team members, it's acceptable. **Document this in the README before
open-sourcing.**

**Acceptance:** POST `{"code": "print(1+1)"}` → `{"ok": true, "stdout": "2\n"}`.

---

### Block L — Schematic upload button in chat

**Owner:** whoever owns Block H
**Effort:** 1 hr
**Depends on:** Block H, Roman's `/upload` endpoint (already on `Roman_Schematic_Parser_UI`)

**Deliverables:**

- Paper-clip button in `ChatPanel` → opens a file chooser filtered for `.SchDoc`
- On select, calls `RunnerClient.uploadFiles(...)` (already exists on Roman's branch — **this block assumes Roman's branch is merged into main first**)
- Sidecar stores the uploaded files under `~/.scopecreep/attachments/<uuid>/` and returns the path set
- Next `/chat/turn` includes `attachments: ["/path/to/foo.SchDoc"]`; orchestrator reads the schematic via the SchDoc parser (Roman's `python/schdoc/`) and appends a summary section to the user message content.

**Merge order implication:** Before Block L is implementable, Roman's branch needs to land on `main`. If Roman hasn't merged by the time we need this, the simplest path is for the data-flywheel contributor to cherry-pick the `feat: add /upload endpoint to sidecar` and `feat: add uploadFiles to RunnerClient with multipart POST` commits.

**Acceptance:** Attach a `.SchDoc` file in chat → next agent turn mentions at least one net or component name visible in the schematic.

---

### Block M — Polish + dry run

**Owner:** all four
**Effort:** 2 hrs

- **Confirmation gate rehearsal** — run through the target demo end-to-end
  with a warm IDE. Time each segment. Keep under 4:00.
- **Fallback screen recording** — record a working pass as insurance against
  live-demo flakes.
- **Sidecar warm-up** — at project-open, kick `/ad/connect` in the background
  so the first chat message doesn't wait on FPGA configuration.
- **Error surface review** — what does the chat look like when PSU is not
  connected? When OpenAI 429s? When Supabase is unreachable? Each should fall
  to a readable chat message, not a stack trace.
- **README update** — post-hackathon cleanup list (re-enable RLS, wire Publish
  button, swap to JCEF for rendering, sandbox `/exec/python`).

---

## 6. Benchy patterns being applied

All cited patterns come from `/home/alex/benchy/frontend/src/app/api/agent/route.ts` and `edge/worker.py` (summaries from prior research turns).

| Benchy pattern | Applied to Scopecreep as |
|---|---|
| 21 tools with example JSON in descriptions | Tool schemas in Block J include a `description` field with a concrete call example; agent learns by example |
| `readFileSync` wiring docs into the system prompt at startup | Block I fetches published profiles from Supabase at turn time and concatenates them into the system prompt |
| User confirmation gate enforced in system prompt, not code | Rule #1 in the Block I base prompt; relies on the LLM honoring the instruction (empirically reliable with gpt-4o) |
| Hardcoded safety clamps in Python | Block G constants `PSU_MAX_VOLTAGE = 5.5`, `PSU_MAX_CURRENT = 1.0` |
| `finally` blocks always disable PSU | Rule #4 in the system prompt + example in every `common operations` section of the instrument profiles |
| Scope returns raw data + stats + PNG URL | Block G's `scope_capture` endpoint builds matplotlib PNG, saves to `~/.scopecreep/artifacts/`, returns `chart_url` alongside `data` + `stats` |
| One global instrument handle, lifespan-managed | Block G's AD driver is a module-level singleton opened on first `/ad/*` call, closed on sidecar shutdown via FastAPI lifespan |
| CORS open, Tailscale = auth | We don't need CORS since everything's localhost, but the "no keys in source, no auth on sidecar" principle stands — sidecar only binds to 127.0.0.1 |
| Compound endpoints (`/run/debug`, `/run/benchmark`) | Post-hackathon: add `/ad/sweep_voltage` and `/ad/measure_dc` as compound endpoints so the agent doesn't need 5 tool calls per measurement |
| 3-column VS Code-style frontend | Scopecreep already uses IDE tool window (ping/profiles/chat as tabs) — conceptual match, different UX substrate |

**Explicitly NOT copied from benchy:**
- LangGraph pipeline service (overkill for 48 hrs)
- Pi edge worker model (everything runs on the dev laptop directly)
- Supabase `runs`/`run_steps`/`measurements` tables (our `profiles` table is the only persistent state; per-turn chat history is session-local)
- Camera feed (no phone camera in this demo)

---

## 7. Risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| FPGA bitstream fails to load on demo laptop (wrong FTDI firmware) | Medium | Block M includes warm-up — catches it pre-demo |
| OpenAI tool-calling produces invalid JSON once in 10 | Low | Orchestrator loop has max-iteration + retry-with-smaller-prompt fallback |
| gpt-4o cost spirals during dry runs | Low | Settings panel has a "token budget" readout (add in Block M); default max_tokens=4096/turn |
| Sidecar venv pip install of `pyftdi + numpy + matplotlib` takes >2 min on cold start | High | Block M: pre-warm venv the night before; document in PLUGIN_DEVELOPMENT.md |
| Roman's branch conflicts with data flywheel at merge time | Medium | Design keeps surfaces separate (Chat tab ≠ Schematic tab; `/chat/*` ≠ `/upload`); merge order `main ← roman ← dataflywheel-chatbot` avoids conflicts |
| RLS still disabled at demo, someone nukes `profiles` | Very low | Demo is on your laptop, anon key is in your head. Still — note in README. |
| Running generated code `exec()`s untrusted Python from a cloud LLM | Low (hackathon), High (production) | Document plainly. Post-hackathon: sandbox via `restrictedpython` or subprocess with seccomp |
| Judge asks "how do you prevent prompt injection from a schematic file" | Medium | Honest answer: we don't, for MVP. Future work: prompt-layer sanitization. |

---

## 8. Day-of-demo checklist

*(copy into a sticky note)*

- [ ] Fresh git pull on the demo laptop
- [ ] `./gradlew buildPlugin` → install the `.zip` into real IntelliJ (not sandbox)
- [ ] Warm the venv: open the IDE once, wait for pip install to complete
- [ ] Connect AD to laptop via known-good USB cable (avoid hub)
- [ ] DUT wired per the cheat-sheet: AD CH1 probe on R1, V+ on PSU rail
- [ ] Paste all three keys (Supabase URL + anon key + Nebius key + OpenAI key)
- [ ] Open Scopecreep tool window → Profiles tab → confirm AD Rev C loads
- [ ] Chat tab → "hi, status check" → agent responds → confirms round-trip works
- [ ] Run the practiced opening prompt once as a warm-up
- [ ] Clear the chat history before the real demo
- [ ] Start screen recording as backup
- [ ] Know the "if it breaks, do this" fallback (usually: restart sidecar)
- [ ] Dev phone on silent, notifications off
- [ ] Confirm `nebius_configured: true` AND `openai_configured: true` via `curl /health`
- [ ] Have the pitch card printed

---

## 9. File manifest when complete

```
src/main/resources/sidecar/
├── analog_discovery/             # ← Block G (vendored from jbhack)
│   ├── driver.py
│   ├── oscilloscope.py
│   ├── awg.py
│   ├── power.py
│   ├── digital.py
│   ├── transport.py
│   ├── jtag.py
│   ├── pti.py
│   └── bitstream/ad_bitstream.bit
├── ad_routes.py                  # ← Block G (FastAPI endpoints)
├── chat.py                       # ← Block J (ChatOrchestrator)
├── exec.py                       # ← Block K (run_user_code)
├── session.py                    # ← Block K (session globals)
├── prompt.py                     # ← Block I (build_system_prompt)
├── worker.py                     # modified to mount ad_routes, /chat/turn, /exec/python
├── requirements.txt              # +pyftdi +numpy +matplotlib
├── config.py                     # (unchanged from flywheel)
├── memory.py                     # (unchanged)
└── research.py                   # (unchanged)

src/main/kotlin/com/scopecreep/
├── ui/
│   ├── ChatPanel.kt              # ← Block H + L
│   ├── ProfilesPanel.kt          # (unchanged)
│   └── MarkdownRenderer.kt       # (unchanged)
├── service/
│   ├── RunnerClient.kt           # + chat methods (sendTurn, execCode)
│   └── CodexProviderManager.kt   # (unchanged)
├── settings/
│   ├── ScopecreepSettings.kt     # (unchanged — openAiApiKey already present)
│   └── ScopecreepSettingsConfigurable.kt  # (unchanged)
└── ScopecreepToolWindowFactory.kt  # + Chat tab registration
```

---

## 10. Sequencing / who owns what

Suggested allocation, 4 people × ~6 wall-clock hours:

| Block | Owner | Starts at | Ends at |
|---|---|---|---|
| G — AD driver + endpoints | Person A (Python-heavy) | hour 0 | hour 4 |
| H — Chat tab UI | Person B (Kotlin-heavy) | hour 0 | hour 3 |
| I — System prompt | Person A (after G) | hour 4 | hour 5 |
| J — Tool-call loop | Person C | hour 4 (needs G started) | hour 7 |
| K — Exec endpoint | Person A | hour 5 | hour 7 |
| L — Schematic upload in chat | Person B (after H) | hour 3 | hour 4 |
| M — Polish + dry run | All four | hour 7 | hour 9 |

Person D: floating — unblock whoever's stuck, own the README update, manage the
merge from Roman's branch.

---

## 11. Open questions (marked for resolution during execution)

- Is Codex CLI needed at all? Our chat orchestrator uses the OpenAI SDK
  directly. The `codex-nebius-setup.sh` infrastructure from the flywheel is
  now orphan code for *this* demo path but still useful post-hackathon for
  letting users route Codex CLI through Nebius in their own coding work.
  **Decision: leave it in, don't use it during the demo.**
- How do we surface scope captures visually in the chat? Options: inline
  `<img>` tag pointing at `/artifacts/<id>.png`; open in a separate IDE tool
  window; skip rendering, just mention the file path. **Decision: inline
  `<img>`.**
- When the agent generates multi-step code (configure + sweep + measure),
  do we break it into multiple code blocks or one? **Decision: one block
  per "complete, runnable script". Multiple blocks only if the agent wants
  to stop between them for user input.**
- Where do the instrument handles live when `run_user_code` runs? **Decision
  (Block K): module-level singletons in `session.py`, same objects the
  `/ad/*` endpoints use. Run-loop just does `exec(code, session_globals)`.**

---

*End of plan. Implementation begins on a branch named `feat/chatbot-hardware-loop`
off of latest `main` once Roman's schematic work is merged.*
