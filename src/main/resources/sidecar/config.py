"""Sidecar env config. Loaded from process env (set by the plugin's
SidecarManager before launching uvicorn)."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Config:
    supabase_url: str | None
    supabase_anon_key: str | None
    nebius_api_key: str | None
    nebius_base_url: str
    nebius_research_model: str
    openai_api_key: str | None
    openai_model: str
    agent_backend_url: str


def load() -> Config:
    return Config(
        supabase_url=os.environ.get("SCOPECREEP_SUPABASE_URL"),
        supabase_anon_key=os.environ.get("SCOPECREEP_SUPABASE_ANON_KEY"),
        nebius_api_key=os.environ.get("SCOPECREEP_NEBIUS_API_KEY"),
        nebius_base_url=os.environ.get(
            "SCOPECREEP_NEBIUS_BASE_URL",
            "https://api.tokenfactory.nebius.com/v1/",
        ),
        # Hard default to the cheapest model; overridden only by explicit opt-in.
        nebius_research_model=os.environ.get(
            "SCOPECREEP_NEBIUS_RESEARCH_MODEL", "google/gemma-3-27b-it"
        ),
        openai_api_key=os.environ.get("OPENAI_API_KEY"),
        openai_model=os.environ.get("OPENAI_MODEL", "gpt-4o-mini"),
        agent_backend_url=os.environ.get(
            "SCOPECREEP_AGENT_BACKEND_URL", "http://127.0.0.1:8000"
        ),
    )
