"""Scopecreep sidecar — minimal MVP skeleton.

Exposes GET /health returning {"status": "ok"}. Hardware endpoints are
stubbed out until the real instrument drivers are ported in from the
backend repo.
"""

from __future__ import annotations

import os
import platform
import time

from fastapi import FastAPI

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
