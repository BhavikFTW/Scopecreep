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
_chat = None  # type: Optional[object]  # ChatOrchestrator, lazy


def _get_store() -> ProfileStore:
    global _store
    if _store is None:
        if not (_config.supabase_url and _config.supabase_anon_key):
            raise HTTPException(status_code=503, detail="supabase not configured")
        from supabase import create_client
        sb = create_client(_config.supabase_url, _config.supabase_anon_key)
        _store = ProfileStore(sb)
    return _store


def _get_store_optional() -> ProfileStore | None:
    try:
        return _get_store()
    except HTTPException:
        return None


def _get_chat():
    global _chat
    if _chat is None:
        if not _config.openai_api_key:
            raise HTTPException(status_code=503, detail="openai api key not configured")
        from openai import OpenAI
        from chat import ChatOrchestrator
        client = OpenAI(api_key=_config.openai_api_key)
        _chat = ChatOrchestrator(
            openai_client=client,
            store=_get_store_optional(),
            model=_config.openai_model,
        )
    return _chat


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
        "openai_configured": bool(_config.openai_api_key),
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


class ChatTurnReq(BaseModel):
    messages: list[dict]


class ExecReq(BaseModel):
    code: str


class SchematicContextReq(BaseModel):
    schematic: dict


_SCHEMATIC_CACHE = os.path.join(
    os.path.expanduser("~"), ".scopecreep", "latest_schematic.json"
)


def _load_cached_schematic() -> dict | None:
    try:
        import json as _json
        if os.path.exists(_SCHEMATIC_CACHE):
            with open(_SCHEMATIC_CACHE) as f:
                return _json.load(f)
    except Exception:  # noqa: BLE001
        return None
    return None


@app.post("/chat/context/schematic")
def set_schematic_context(req: SchematicContextReq) -> dict:
    import json as _json
    os.makedirs(os.path.dirname(_SCHEMATIC_CACHE), exist_ok=True)
    with open(_SCHEMATIC_CACHE, "w") as f:
        _json.dump(req.schematic, f)
    try:
        _get_chat().set_schematic_context(req.schematic)
    except HTTPException:
        # chat not wired yet (no OpenAI key) — still persist for later.
        pass
    return {"status": "ok"}


@app.post("/chat/turn")
def chat_turn(req: ChatTurnReq) -> dict:
    try:
        chat = _get_chat()
        # Rehydrate schematic context from disk on first turn after restart.
        if chat.schematic_context is None:
            cached = _load_cached_schematic()
            if cached is not None:
                chat.set_schematic_context(cached)
        result = chat.run_turn(req.messages)
    except HTTPException:
        raise
    except Exception as exc:  # noqa: BLE001
        import traceback as _tb
        detail = f"{type(exc).__name__}: {exc}\n{_tb.format_exc()[-800:]}"
        raise HTTPException(status_code=500, detail=detail) from exc
    return {
        "message": result.assistant_message,
        "code_blocks": [
            {"path": b.path, "code": b.code} for b in result.code_blocks
        ],
    }


@app.post("/exec/python")
def exec_python(req: ExecReq) -> dict:
    from exec_runner import run_user_code
    r = run_user_code(req.code)
    return {"ok": r.ok, "stdout": r.stdout, "stderr": r.stderr}


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
