# File Input UI — Design Spec

**Date:** 2026-04-18  
**Branch:** feat/ui-development  
**Status:** Approved

---

## Overview

Replace the "Ping sidecar" UI in `ScopecreepToolWindowFactory.kt` with a two-file input form. The user selects a schematic file and a PCB file, then clicks Run to POST both to the sidecar. The sidecar returns paths/bytes that the plugin routes into a Claude API agent call downstream.

---

## Layout

`ScopecreepPanel` uses `BorderLayout`. The center holds a `GridBagLayout` form with two labeled rows:

| Row | Label | File filter |
|-----|-------|-------------|
| 1 | Schematic | `*.png`, `*.schdoc` |
| 2 | PCB | `*.png`, `*.pcbdoc` |

Each row contains:
- A `JBLabel` (fixed width) for the field name
- A read-only `JTextField` showing the selected file path
- A "Browse…" `JButton` that opens a `JFileChooser` filtered to the allowed extensions

A "Run" `JButton` is anchored to the SOUTH of the panel. It is **disabled** until both text fields are non-empty.

The existing ping button, status label, and `ping()` method are removed entirely.

---

## Data Flow

1. User selects both files → Run button enables
2. User clicks Run → button disabled, text changes to "Sending…"
3. `RunnerClient.uploadFiles(schematic: File, pcb: File)` is called on a pooled thread (same pattern as existing `ping()`)
4. OkHttp `MultipartBody` with two named parts (`schematic`, `pcb`) is POSTed to `POST /upload` on the sidecar
5. `worker.py` saves both files to a temp dir, returns `{"status": "ok", "schematic": "<path>", "pcb": "<path>"}`
6. On `Result.Ok` → status label shows "Uploaded." for 3 seconds, Run re-enables
7. On `Result.Err` → status label shows the error message, Run re-enables

---

## Files Changed

| File | Change |
|------|--------|
| `ScopecreepToolWindowFactory.kt` | Replace `ScopecreepPanel` with new file-input UI |
| `service/RunnerClient.kt` | Add `uploadFiles(schematic: File, pcb: File): Result` |
| `sidecar/worker.py` | Add `POST /upload` endpoint |

---

## Out of Scope

- Results display / agent response rendering (future)
- Drag-and-drop file input
- Validation of file contents (only extension filtering)
