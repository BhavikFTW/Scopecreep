"""User-code executor for the chat loop's Run buttons.

Runs arbitrary Python in the sidecar venv, captures stdout/stderr. This is
deliberately *not* sandboxed — the UX contract is "the user saw the code and
clicked Run", so this is equivalent to them pasting into a REPL. Document the
security posture in README before open-sourcing.
"""

from __future__ import annotations

import io
import traceback
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import dataclass
from typing import Any


@dataclass
class ExecResult:
    ok: bool
    stdout: str
    stderr: str


def _session_globals() -> dict[str, Any]:
    import requests  # noqa: WPS433

    try:
        import numpy as np  # noqa: WPS433
    except ImportError:
        np = None  # type: ignore[assignment]

    g: dict[str, Any] = {
        "__name__": "__scopecreep_chat__",
        "requests": requests,
        "API": "http://127.0.0.1:8000",
    }
    if np is not None:
        g["np"] = np
    return g


def run_user_code(code: str) -> ExecResult:
    stdout = io.StringIO()
    stderr = io.StringIO()
    g = _session_globals()
    try:
        with redirect_stdout(stdout), redirect_stderr(stderr):
            exec(compile(code, "<chat>", "exec"), g)  # noqa: S102
        return ExecResult(ok=True, stdout=stdout.getvalue(), stderr=stderr.getvalue())
    except BaseException:  # noqa: BLE001
        return ExecResult(
            ok=False,
            stdout=stdout.getvalue(),
            stderr=stderr.getvalue() + traceback.format_exc(),
        )
