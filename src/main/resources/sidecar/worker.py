"""Scopecreep sidecar.

Exposes /health, /upload (schematic + PCB), and /memory/* (profile read/write
+ Nebius-backed research)."""

from __future__ import annotations

import os
import platform
import shutil
import tempfile
import time
from typing import Optional

from fastapi import FastAPI, File, HTTPException, UploadFile
from pydantic import BaseModel

from config import Config, load as load_config
from memory import Profile, ProfileStore
from research import Researcher

app = FastAPI(title="Scopecreep Sidecar", version="0.0.1")

_STARTED_AT = time.time()
_config: Config = load_config()
_store: Optional[ProfileStore] = None
_researcher: Optional[Researcher] = None


def _get_store() -> ProfileStore:
    global _store
    if _store is None:
        if not (_config.supabase_url and _config.supabase_anon_key):
            raise HTTPException(status_code=503, detail="supabase not configured")
        from supabase import create_client
        sb = create_client(_config.supabase_url, _config.supabase_anon_key)
        _store = ProfileStore(sb)
    return _store


def _get_researcher() -> Researcher:
    global _researcher
    if _researcher is None:
        if not _config.nebius_api_key:
            raise HTTPException(status_code=503, detail="nebius api key not configured")
        from openai import OpenAI
        client = OpenAI(
            base_url=_config.nebius_base_url,
            api_key=_config.nebius_api_key,
        )
        _researcher = Researcher(client=client, model=_config.nebius_research_model)
    return _researcher


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "service": "scopecreep-sidecar",
        "version": app.version,
        "uptime_seconds": round(time.time() - _STARTED_AT, 3),
        "pid": os.getpid(),
        "python": platform.python_version(),
        "supabase_configured": bool(_config.supabase_url and _config.supabase_anon_key),
        "nebius_configured": bool(_config.nebius_api_key),
    }


@app.post("/upload")
async def upload(
    schematic: UploadFile = File(...),
    pcb: UploadFile = File(...),
) -> dict:
    tmp_dir = tempfile.mkdtemp(prefix="scopecreep_")
    schematic_path = os.path.join(tmp_dir, os.path.basename(schematic.filename or "schematic"))
    pcb_path = os.path.join(tmp_dir, os.path.basename(pcb.filename or "pcb"))
    with open(schematic_path, "wb") as f:
        shutil.copyfileobj(schematic.file, f)
    with open(pcb_path, "wb") as f:
        shutil.copyfileobj(pcb.file, f)
    return {"status": "ok", "schematic": schematic_path, "pcb": pcb_path}


class SearchReq(BaseModel):
    query: str
    limit: int = 5


class RememberReq(BaseModel):
    kind: str
    slug: str
    title: str
    content: str
    status: str = "draft"


class ResearchReq(BaseModel):
    instrument_name: str


@app.get("/memory/recall/{slug}")
def memory_recall(slug: str) -> dict:
    profile = _get_store().recall(slug)
    if profile is None:
        raise HTTPException(status_code=404, detail=f"no published profile for {slug}")
    return profile.__dict__


@app.post("/memory/search")
def memory_search(req: SearchReq) -> list[dict]:
    return [p.__dict__ for p in _get_store().search(req.query, req.limit)]


@app.post("/memory/remember")
def memory_remember(req: RememberReq) -> dict:
    new_id = _get_store().remember(Profile(
        id=None, kind=req.kind, slug=req.slug, title=req.title,
        content=req.content, status=req.status,
    ))
    return {"id": new_id, "status": req.status}


@app.post("/memory/publish/{profile_id}")
def memory_publish(profile_id: str) -> dict:
    _get_store().publish(profile_id)
    return {"id": profile_id, "status": "published"}


@app.post("/memory/research")
def memory_research(req: ResearchReq) -> dict:
    md = _get_researcher().draft_profile(req.instrument_name)
    slug = req.instrument_name.lower().replace(" ", "-").replace("/", "-")[:64]
    title = req.instrument_name
    # Hackathon mode: auto-publish so the researched profile appears in the
    # list immediately. Post-MVP, flip this back to "draft" and wire the
    # Publish button to flip it later after user review.
    new_id = _get_store().remember(Profile(
        id=None, kind="device", slug=slug, title=title,
        content=md, status="published",
    ))
    return {"id": new_id, "slug": slug, "title": title, "content": md}
