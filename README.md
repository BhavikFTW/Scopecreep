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
| Real instrument drivers | next milestone |
| AI agent integration | next milestone |

## Requirements

- **JDK 21** (required by IntelliJ Platform 2025.2; Gradle toolchain auto-fetches it via foojay)
- **Python 3.11+** on `PATH` (the plugin builds its own venv from bundled `requirements.txt`)
- **IntelliJ IDEA 2025.2+** for the installed plugin

## Run from source

```bash
./gradlew runIde
```

This launches a sandbox IDE with the plugin pre-loaded. On first project open:

1. Tool window **Scopecreep** appears on the right sidebar.
2. Sidecar files are extracted to `~/.scopecreep/sidecar/`.
3. A venv is created at `~/.scopecreep/venv/` and dependencies are installed.
4. `uvicorn worker:app` starts on `127.0.0.1:8420` (configurable).
5. Click **Ping sidecar** — the label flips to `pong — {...health json...}`.

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
