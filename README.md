# Scopecreep

> Software-defined hardware control inside JetBrains IDEs.

Scopecreep turns an IDE into an agentic hardware testbench. The plugin boots a
local Python sidecar that drives real lab instruments (oscilloscope, programmable
PSU, DUT serial), while the IDE owns the UI, the AI conversation, and the test
orchestration.

This is the **MVP skeleton** — it brings up the tool window, spawns the managed
sidecar, and pings a `/health` endpoint. Real instrument I/O, RCA, and agent
integrations ride on top of this plumbing in later milestones.

## Status

| Layer | State |
|-------|-------|
| Tool window + Ping button | ready |
| Managed Python sidecar (venv + uvicorn) | ready |
| `/health` round-trip | ready |
| Settings panel (runner host + port) | ready |
| Profiles / memory layer (Supabase) | ready |
| Agent backend bundled (port 8000) | ready |
| Schematic tab — `/schematic/parse` render | ready |
| Agent tab — session lifecycle + probe prompts | ready |
| Waveform tab — measurement table | ready |
| Chat tab — `codex exec` wrapper | ready |
| Firmware tab — Supabase `firmware_jobs` dashboard | ready (pipeline still out-of-process) |

For a full step-by-step install, first-boot walk-through, and validation
matrix, see **[docs/INSTALL_AND_VALIDATE.md](docs/INSTALL_AND_VALIDATE.md)**.

## Install

The plugin is distributed as a single zip on the **[Releases page](https://github.com/BhavikFTW/Scopecreep/releases)** — you do **not** need to clone this repo or run Gradle to use it.

1. Download the latest `Scopecreep-x.y.z.zip`.
2. In your JetBrains IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the zip, restart the IDE.
3. On first project open, the plugin bootstraps a local Python venv at `~/.scopecreep/venv/` and launches two uvicorn workers (`:8420` for profiles, `:8000` for the agent backend). First run takes 30–90 s; progress and errors surface in the **Schematic Testbench** tab.

Requires `python3.11+` on `PATH` and a JetBrains IDE 2025.2 or newer.

## Requirements

- **JDK 21** (required by IntelliJ Platform 2025.2; Gradle toolchain auto-fetches it via foojay)
- **Python 3.11+** on `PATH` (the plugin builds its own venv from bundled `requirements.txt`)
- **IntelliJ IDEA 2025.2+** for the installed plugin

## Build from source (contributors)

```bash
git clone --recurse-submodules https://github.com/BhavikFTW/Scopecreep
cd Scopecreep
./gradlew runIde         # sandbox IDE for iteration
./gradlew buildPlugin    # produce build/distributions/Scopecreep-*.zip
```

The Python backend is a git submodule at `backend/`. Always clone with `--recurse-submodules`, or run `git submodule update --init --recursive` after cloning. To build against a local checkout of `Scopecreep-hardware` outside the submodule, pass `-Pbenchy.backend.path=/absolute/path/to/python` to any Gradle invocation.

This launches a sandbox IDE with the plugin pre-loaded. On first project open:

1. Tool window **Scopecreep** appears on the right sidebar.
2. Sidecar files are extracted to `~/.scopecreep/sidecar/`.
3. A venv is created at `~/.scopecreep/venv/` and dependencies are installed.
4. `uvicorn worker:app` starts on `127.0.0.1:8420` (profile/memory) and
   `uvicorn agent_worker:app` starts on `127.0.0.1:8000` (agent backend +
   hardware). Both are configurable under **Settings → Tools → Scopecreep**.
5. Click **Ping sidecar** — the label flips to `pong — {...health json...}`.

## Agent session flow

1. Open the **Schematic** tab → pick a `.SchDoc` → **Parse** to view the
   Markdown summary. Use **Agent** tab's **Load .SchDoc → JSON** button to
   populate the structured JSON the session endpoint expects.
2. Switch to the **Agent** tab and click **Start session**. The plugin posts
   to `POST /agent/sessions` on the agent backend (port 8000) and polls
   `GET /agent/sessions/{id}` at ~1 Hz.
3. When the status is **PROBE_REQUIRED**, the probe prompt card shows the
   label, net, location hint, and instructions. Place the probe and click
   **Resume (probe placed)**.
4. When the session reaches **COMPLETE** or **FAILED**, the report is
   rendered in the right-hand pane and the **Waveform** tab is populated with
   per-probe measurements.

## Firmware tab

The firmware generation pipeline (LangGraph, in `~/benchy/pipeline/`) lives
out of this repo. This plugin only wires the UI to the shared Supabase
`firmware_jobs` table: the **Generate** button inserts a row; rows are
polled every 2s and rendered into a stage timeline. When the pipeline is
not running, rows stay in `queued` state indefinitely — expected.

Requires a Supabase project with the `002_firmware_jobs.sql` migration
applied (see `supabase/migrations/`).

## Build a distributable

```bash
./gradlew buildPlugin
```

Produces `build/distributions/Scopecreep-0.0.1.zip`. Install in a real IDE via
**Settings → Plugins → gear → Install Plugin from Disk…**.

## Settings

**Settings → Tools → Scopecreep** exposes:

- `Runner host` — where the sidecar listens (default `127.0.0.1`)
- `Runner port` — default `8420`

Changes apply on the next sidecar restart.

## Layout

```
src/
├── main/kotlin/ai/galois/scopecreep/
│   ├── ScopecreepToolWindowFactory.kt    # tool window + panel
│   ├── service/RunnerClient.kt           # OkHttp → sidecar
│   ├── sidecar/
│   │   ├── SidecarManager.kt             # venv + uvicorn lifecycle
│   │   └── SidecarActivity.kt            # ProjectActivity startup hook
│   └── settings/
│       ├── ScopecreepSettings.kt         # PersistentStateComponent
│       └── ScopecreepSettingsConfigurable.kt
├── main/resources/
│   ├── META-INF/plugin.xml
│   └── sidecar/                          # bundled into the plugin JAR
│       ├── worker.py
│       └── requirements.txt
└── test/kotlin/ai/galois/scopecreep/
    └── ScopecreepSettingsTest.kt
```

## Roadmap

- **M1** Port real instrument drivers (DPS-150, Analog Discovery) into `worker.py`
- **M2** JCEF-based waveform panel (Recharts over `/scope/capture` artifacts)
- **M3** Codex chat tab
- **M4** "Flash & Test" run configuration type
- **M5** Gutter inlays showing last-measured values next to `pinMode`/`ledcWrite`
- **M6** Pre-commit benchmark hook (power/timing delta on HEAD vs staged)

## License

MIT — see [LICENSE](LICENSE).
