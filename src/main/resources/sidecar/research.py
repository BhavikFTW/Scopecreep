"""Nebius-backed research flow. Drafts an instrument-profile MD using the
OpenAI SDK pointed at Nebius Token Factory."""

from __future__ import annotations

from typing import Any


_SYSTEM_PROMPT = """\
You are drafting an instrument-profile Markdown document for the Scopecreep
plugin's memory layer. Your draft will be reviewed by a human before
publication. Follow the exact template below. Do not invent capabilities
or APIs — mark any unknowns as TODO.

## Required sections (in this order)

1. Identity (vendor, model, canonical slug, connectivity)
2. Capabilities (with concrete numbers: sample rates, voltage ranges, etc.)
3. Pin layout (table form, with wire colors if applicable)
4. Safety limits (max voltages, currents, destructive-misuse warnings)
5. Common operations (short Python pseudocode showing intent)
6. Known gotchas (bench-earned wisdom — be humble, list TODO where unknown)
7. Sources (URLs you drew from; datestamps; confidence 1–5)

Return ONLY the Markdown document. No preamble. No trailing commentary.
"""


class Researcher:
    def __init__(self, client: Any, model: str):
        self.client = client
        self.model = model

    def draft_profile(self, instrument_name: str) -> str:
        user = (
            f"Draft an instrument-profile Markdown for: {instrument_name}\n"
            "If you are unsure about any numeric spec or pin mapping, say TODO."
        )
        response = self.client.chat.completions.create(
            model=self.model,
            messages=[
                {"role": "system", "content": _SYSTEM_PROMPT},
                {"role": "user", "content": user},
            ],
            temperature=0.2,
            max_tokens=4096,
        )
        return response.choices[0].message.content or ""
