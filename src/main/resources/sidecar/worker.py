"""Scopecreep sidecar — minimal MVP skeleton.

Exposes GET /health returning {"status": "ok"} and POST /upload for file
uploads. Hardware endpoints are stubbed out until the real instrument drivers
are ported in from the backend repo.
"""

from __future__ import annotations

import os
import platform
import shutil
import tempfile
import time

from fastapi import FastAPI, File, UploadFile

app = FastAPI(title="Scopecreep Sidecar", version="0.0.1")

_STARTED_AT = time.time()


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "service": "scopecreep-sidecar",
        "version": app.version,
        "uptime_seconds": round(time.time() - _STARTED_AT, 3),
        "pid": os.getpid(),
        "python": platform.python_version(),
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
