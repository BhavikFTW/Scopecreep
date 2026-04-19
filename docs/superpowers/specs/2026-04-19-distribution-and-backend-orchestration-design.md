# Distribution & Backend Orchestration — Design Spec

**Status:** approved for implementation
**Author:** Bhavik + Claude, session 2026-04-19
**Depends on:** `feat/plugin-agent-integration` (3-pillar UI consolidation landed in `132e39a`)

This spec covers two related fixes:

1. **Phase 1 — Distribution.** Collapse the current two-repo + manual-colocation + local-gradle install story to a single zip pulled from a GitHub Release.
2. **Phase 2 — Orchestration visibility.** Replace the silent "Error connecting to server" failure mode with a structured status strip + classified error messages in the tool window.

Phase 2 is built on top of Phase 1 and assumes it has landed.

---

## Context

The Scopecreep plugin currently depends on a Python backend that lives in a **separate repo** (`Kenneth-Ross/Scopecreep-hardware`) under `python/`. At build time, Gradle's `bundleBenchyBackend` task reads `../python` (relative to the Scopecreep plugin repo) and vendors the tree into `src/main/resources/sidecar/benchy/`.

If `../python` is absent at build time — which happens any time the plugin repo is cloned standalone — the task silently writes a placeholder `agent_worker.py` that raises `SystemExit`. The resulting zip installs cleanly but the agent worker dies on startup, and the Schematic Testbench pillar reports only:

> Error connecting to server

This happened to a teammate on macOS today (session `2026-04-19`). The install story that teammate had to follow was:

1. Clone `Kenneth-Ross/Scopecreep-hardware` (parent, has `python/`).
2. Clone `BhavikFTW/Scopecreep` (plugin) as a sibling, not inside the parent.
3. Install JDK 21.
4. `./gradlew buildPlugin` in the plugin checkout.
5. Manually copy the produced zip to the Mac, install via IDE.
6. Wait for a silent venv bootstrap with no progress indication.
7. If anything fails, read `idea.log` to figure out why.

**Goal:** a single URL → install-from-disk → working plugin, with visible status on every subprocess the plugin manages.

---

## Phase 1 — One-zip distribution

### 1.1 Repo layout: add `Scopecreep-hardware` as a submodule

Inside `BhavikFTW/Scopecreep`:

```
Scopecreep/
├── backend/                          ← git submodule → Kenneth-Ross/Scopecreep-hardware
│   └── python/                       ← the FastAPI + drivers tree the bundler reads
├── src/main/…
├── build.gradle.kts
└── .github/workflows/release.yml
```

- Submodule path: `backend`. Remote: `https://github.com/Kenneth-Ross/Scopecreep-hardware.git`. Pinned to a specific SHA per release.
- `build.gradle.kts` default for `benchy.backend.path` changes from `../python` to `backend/python`.
- The `-Pbenchy.backend.path=/abs/path` override stays, so a dev iterating on the backend outside the submodule can still build.

**Release ritual:**

```
cd backend && git pull origin main && cd ..
git add backend
git commit -m "bump backend to <sha>"
git tag v0.1.x
git push --follow-tags
```

Tag push triggers CI → zip attached to Release.

### 1.2 CI workflow (`.github/workflows/release.yml`)

- **Triggers:**
  - `push: tags: ['v*']` — cut a real release, attach zip to GitHub Release.
  - `push: branches: [main]` — build zip, upload as a workflow artifact (no release). So teammates on `main` can grab a "nightly" build from the Actions tab.
  - `workflow_dispatch` — manual button for re-running a build.
- **Runner:** `ubuntu-latest`. The zip is OS-neutral because:
  - The plugin is JVM code.
  - The bundled Python backend is source, not compiled binaries.
  - The venv is bootstrapped per-user on the first plugin launch from their system `python3`.
- **Steps:**
  1. `actions/checkout@v4` with `submodules: recursive`.
  2. `actions/setup-java@v4` — Temurin 21.
  3. `gradle/actions/setup-gradle@v4` for Gradle's build cache.
  4. `./gradlew buildPlugin --no-daemon`.
  5. On tag push: `softprops/action-gh-release@v2` uploads `build/distributions/Scopecreep-*.zip`.
  6. On branch push: `actions/upload-artifact@v4` with retention 14 days.

### 1.3 Install flow after Phase 1

README shrinks to:

> **Install:**
> 1. Grab the latest `Scopecreep-x.y.z.zip` from [Releases](https://github.com/BhavikFTW/Scopecreep/releases/latest).
> 2. In your JetBrains IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the zip, restart.
> 3. On first open, the plugin bootstraps a local Python venv. Progress shows in the Schematic Testbench tab header; first run takes 30–90 s.

No git, no gradle, no co-location.

### 1.4 Dev-on-plugin flow (unchanged-ish)

Contributors who want to rebuild locally:

```
git clone --recurse-submodules https://github.com/BhavikFTW/Scopecreep
cd Scopecreep
./gradlew buildPlugin
```

Contributors actively editing the backend:

```
# Point the bundler at a working copy instead of the submodule.
./gradlew buildPlugin -Pbenchy.backend.path=/abs/path/to/Scopecreep-hardware/python
```

---

## Phase 2 — Visible orchestration & classified errors

### 2.1 `ServiceStatus` model

Introduce a sealed hierarchy in `com.scopecreep.sidecar`:

```kotlin
sealed class ServiceStatus {
    object Idle : ServiceStatus()
    data class Bootstrapping(val step: String) : ServiceStatus()
    object Running : ServiceStatus()
    data class Failed(val message: String, val hint: String?, val logTail: String) : ServiceStatus()
}
```

`SidecarManager` tracks three logical services:

| Key | What it represents |
|---|---|
| `venv` | `~/.scopecreep/venv` creation + `pip install -r requirements.txt` |
| `profile` | uvicorn process for `worker:app` on `:8420` (profile sidecar, memory/research) |
| `agent` | uvicorn process for `agent_worker:app` on `:8000` (FastAPI exposing `/schematic/parse` + agent sessions) |

`SidecarManager` exposes:

```kotlin
fun currentStatus(): Map<String, ServiceStatus>
fun addStatusListener(cb: (Map<String, ServiceStatus>) -> Unit): Disposable
```

Listeners are fired on the AWT thread.

### 2.2 Status strip in the Schematic Testbench toolbar

Today the pillar's toolbar has a single "sidecar: ok" JLabel. Replace with a compact three-dot row:

```
●venv  ●sidecar :8420  ●agent :8000     [Copy diagnostics…]
```

Dot semantics:
- Green filled — `Running`.
- Amber filled, slow pulse — `Bootstrapping`. Tooltip = current step (e.g. `pip install openai`).
- Red filled — `Failed`. Tooltip = `message`. Click opens a dialog with the full `logTail` (scrollable, monospace).
- Grey outline — `Idle`.

The existing "Ping" button (which only probes `:8420`) is removed; the three-dot strip obviates it.

### 2.3 First-run UX

Today the user opens the tool window and sees… nothing, while venv creation and pip install block in the background. With Phase 2:

1. Tool window opens → status strip shows `venv: Bootstrapping (creating venv)`, `profile/agent: Idle`.
2. Pip progresses → tooltip updates to `pip install fastapi (1/12)`, `pip install openai (2/12)`, …
3. Venv done → `venv: Running`. `profile` and `agent` transition to `Bootstrapping (starting)` then `Running` within a few seconds.
4. If any failed, its dot goes red with a hint.

No modal dialog is shown; the user can still read other panels or edit settings while bootstrap runs.

### 2.4 Error classification

`SidecarManager.spawn()` currently reads the exit code and stashes `lastError`. Extend it so that when a subprocess exits before its port is listening:

| Detection (stderr regex) | `message` | `hint` |
|---|---|---|
| `ModuleNotFoundError: No module named '(\w+)'` | "Missing Python dep: `$1`" | "Delete `~/.scopecreep/venv` and reopen the project to reinstall." |
| `Address already in use` | "Port `:{port}` is already in use." | "Another Scopecreep instance running? Check `lsof -i :{port}`." |
| `Permission denied.*pyftdi` / `USBError` | "FTDI device not accessible." | "On macOS, install libusb via Homebrew. On Linux, add yourself to `plugdev`." |
| stderr blank, exit code ≠ 0 | "Subprocess exited with code `{code}`." | "Click the dot for the last 200 log lines." |

OpenAI-key-missing is detected a different way: on a failed REST call with HTTP 500, `AgentClient` inspects the response body for the sentinel `"openai key not configured"` and raises it as a Failed status on the `agent` service.

### 2.5 Diagnostic dump

The "Copy diagnostics…" button produces, on the clipboard, a single Markdown block:

```
# Scopecreep diagnostics
Plugin version: 0.1.2
Generated: 2026-04-19T18:42:11Z

## Settings (redacted)
- runnerHost: 127.0.0.1
- runnerPort: 8420
- agentPort: 8000
- openAiApiKey: sk-****1a2b
- supabaseAnonKey: ****
- openAiModel: gpt-4o

## Service status
- venv:    Running
- profile: Running
- agent:   Failed — Missing Python dep: fastapi (pip install failed?)

## Agent log tail
<last 50 lines>
```

Usage: paste into a GitHub issue or Slack when asking for help. No secret keys leak; only last 4 characters of `openAiApiKey`.

---

## Non-goals

- **JetBrains Marketplace listing.** Deferred until after the hackathon.
- **Signing / notarizing the zip.** Deferred.
- **Bundling a hermetic Python interpreter** (uv, pyoxidizer, PyInstaller). We continue to assume the user has `python3.11+` on `PATH`. README documents this.
- **Merging the two uvicorn workers into one FastAPI app.** Possible simplification, but it's a backend refactor owned by Kenneth; out of scope here.
- **Migrating to a monorepo.** Explicitly rejected in favor of submodule.

---

## Files & components that will change

### Phase 1
| File | Change |
|---|---|
| `.gitmodules` | **NEW** — pins `backend` → `Scopecreep-hardware`. |
| `backend/` | **NEW** — submodule directory. |
| `build.gradle.kts` | `benchy.backend.path` default `../python` → `backend/python`. |
| `.github/workflows/release.yml` | **NEW** — the CI described in §1.2. |
| `README.md` | Replace build-from-source instructions with the Releases install flow. Add "For plugin contributors" subsection pointing at `--recurse-submodules`. |

### Phase 2
| File | Change |
|---|---|
| `src/main/kotlin/com/scopecreep/sidecar/ServiceStatus.kt` | **NEW** — the sealed hierarchy. |
| `src/main/kotlin/com/scopecreep/sidecar/SidecarManager.kt` | Track per-service status; fire listeners on transitions; classify stderr on subprocess exit. |
| `src/main/kotlin/com/scopecreep/ui/ServiceStatusStrip.kt` | **NEW** — the three-dot Swing component + tooltip/click dialog. |
| `src/main/kotlin/com/scopecreep/ui/SchematicTestbenchPanel.kt` | Replace the current health label + Ping button with `ServiceStatusStrip` + "Copy diagnostics" button. |
| `src/main/kotlin/com/scopecreep/ui/DiagnosticsDump.kt` | **NEW** — builds the Markdown block for clipboard. |
| `src/main/kotlin/com/scopecreep/service/AgentClient.kt` | Inspect 500-response bodies for `"openai key not configured"` sentinel; when matched, push `ServiceStatus.Failed` into the `agent` service slot via a new `SidecarManager.reportRestFailure(tag, …)` hook. |

Nothing in the Python backend, Supabase schema, or plugin.xml changes.

---

## Verification

### Phase 1
1. `.gitmodules` committed; `backend/` pointing at the latest `Scopecreep-hardware/main` SHA.
2. Fresh clone on an empty machine: `git clone --recurse-submodules <plugin-url> && cd Scopecreep && ./gradlew buildPlugin` produces a zip whose embedded `sidecar/benchy/agent_worker.py` is the real shim (`from api.server import app`), not the SystemExit placeholder.
3. Same fresh clone **without** `--recurse-submodules`: build fails with a clear Gradle error pointing at the missing submodule (not a silently-stubbed zip). Use `required = true` in the `.gitmodules` entry and fail the `bundleBenchyBackend` task if `backend/python` is absent.
4. CI: push a `v0.0.2-rc1` tag to the plugin repo; GitHub Release appears with `Scopecreep-0.0.2-rc1.zip` attached. Download, install on a Mac, verify `curl 127.0.0.1:8000/health` answers within 60 s of first open.

### Phase 2
5. Open the tool window with the sidecar binary deleted (`rm ~/.scopecreep/venv/bin/python`): the `venv` dot goes red with message "Missing Python dep: …" and the dialog shows the pip log tail.
6. Start another plugin instance in a second IDE so port 8000 is in use: `agent` dot goes red with message "Port :8000 is already in use."
7. Blank OpenAI key + schematic parse attempt: `agent` dot transitions from Running to Failed with hint "OpenAI key not set — Settings → Scopecreep".
8. Click "Copy diagnostics" → paste into an editor → contains the three statuses, log tails, and a redacted settings block (no full API keys).

---

## Sequencing

Phase 1 ships standalone first (CI + submodule + zip) because it unblocks teammates immediately. Phase 2 lands on top once Phase 1 is in `main`. Each phase is one implementation plan / branch.
