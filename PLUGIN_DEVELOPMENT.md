# Plugin Development Guide

How the Scopecreep team builds, runs, and ships the plugin. Read this before
touching any code.

## How we work

- All of us are SSH'd into the **same dev server** (Tailscale).
- We **write code on the server**. Shared filesystem, shared tooling, shared
  Python venv for the sidecar when we need to iterate on it.
- We **run the sandbox IDE locally** on our own laptops — `./gradlew runIde`
  opens a GUI, which doesn't work over SSH.
- Git is the bridge: push from the server → pull on the laptop → run.

Short loop per person:

```
edit on server  →  commit+push  →  pull on laptop  →  ./gradlew runIde  →  verify
```

That's it.

---

## Prerequisites

### On your laptop (for running the sandbox IDE)

- **JDK 21** (Temurin recommended)
  - macOS: `brew install --cask temurin@21`
  - Linux: `sudo apt install temurin-21-jdk` (after adding the Adoptium repo) or
    use [SDKMAN](https://sdkman.io): `sdk install java 21-tem`
- **`JAVA_HOME` set to the JDK 21 path**
  - macOS: `echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 21)' >> ~/.zshrc && source ~/.zshrc`
  - Verify: `java -version` prints `21.x`
- **Python 3.11+** on PATH (the plugin builds its own venv, but needs a base
  Python to do it): `python3 --version`
- **Git**
- ~3 GB free disk space (Gradle caches IntelliJ + JBR)
- **IntelliJ IDEA 2025.2+** — only if you want to install the built `.zip` into
  your daily IDE. Not needed for `./gradlew runIde`.

### On the dev server (for editing code + server-side checks)

- Already provisioned. If something's missing, flag it in chat.

---

## First-time setup (laptop)

```bash
git clone https://github.com/BhavikFTW/Scopecreep.git
cd Scopecreep
./gradlew runIde
```

First run takes 3–10 min — Gradle downloads IntelliJ Community 2025.2.6.1 (~1.5
GB) + JBR (~300 MB) into `~/.gradle/caches/`. Subsequent runs start in 20–60 sec.

When the sandbox IDE pops up:

1. Open any project (or create an empty one)
2. Look for **Scopecreep** on the right sidebar → click it
3. Wait ~30–60 sec on first open while the sidecar builds its venv
4. Click **Ping sidecar** → label flips to `pong — {"status":"ok",...}`

If step 4 fails, see [Troubleshooting](#troubleshooting).

---

## Daily loop

### On the server (writing code)

```bash
cd /home/<you>/jbhack/Scopecreep    # or wherever you cloned
git pull origin main
git checkout -b feat/<your-feature>
# … edit files …
git add -A
git commit -m "feat: add <thing>"
git push -u origin feat/<your-feature>
```

Then open a PR on GitHub: <https://github.com/BhavikFTW/Scopecreep/pulls>.

### On your laptop (testing)

```bash
git fetch
git checkout feat/<their-feature>    # or `main` after merge
./gradlew runIde
```

Test the change in the sandbox IDE. If it breaks, comment on the PR.

### Stopping the sandbox IDE

Just close the window. The sidecar Python process is killed automatically by
the plugin's `Disposable` hook. If `uvicorn` is still alive for some reason
(crash during dev):

```bash
pkill -f "uvicorn worker:app"
```

---

## Branching rules

- `main` is always runnable. If `./gradlew runIde` is broken on main, fix it
  before anything else.
- Feature branches: `feat/<short-description>` (e.g. `feat/scope-capture-tab`)
- Bugfix branches: `fix/<short-description>`
- **One PR per feature.** Small PRs merge fast. Big PRs block everyone.
- Squash-merge into main. Keep commit history tidy.

---

## Code tour — where things live

```
src/main/kotlin/ai/galois/scopecreep/
├── ScopecreepToolWindowFactory.kt    # right-sidebar tool window + Swing panel
├── service/
│   └── RunnerClient.kt               # OkHttp client → http://<host>:<port>/...
├── sidecar/
│   ├── SidecarManager.kt             # spawns uvicorn, manages venv, kills on dispose
│   └── SidecarActivity.kt            # ProjectActivity — kicks sidecar on project open
└── settings/
    ├── ScopecreepSettings.kt         # PersistentStateComponent (host + port)
    └── ScopecreepSettingsConfigurable.kt  # Settings → Tools → Scopecreep panel

src/main/resources/
├── META-INF/plugin.xml               # extension points, service/tool-window registration
└── sidecar/
    ├── worker.py                     # FastAPI app — bundled into plugin JAR
    └── requirements.txt              # fastapi, uvicorn

src/test/kotlin/ai/galois/scopecreep/
└── ScopecreepSettingsTest.kt         # BasePlatformTestCase example
```

**Where to add code:**

| If you're adding… | Put it in… |
|---|---|
| A new HTTP endpoint (hardware command) | `src/main/resources/sidecar/worker.py` + a new `RunnerClient` method |
| A new Python dep | `src/main/resources/sidecar/requirements.txt` (and bump version in `worker.py` if relevant) |
| A new button / tab in the tool window | `ScopecreepToolWindowFactory.kt` (or split into its own file under `ui/`) |
| A new config option | `ScopecreepSettings.State` data class + a row in `ScopecreepSettingsConfigurable` |
| A background job the plugin runs | New `@Service` class, project- or app-level |
| A test | `src/test/kotlin/ai/galois/scopecreep/` — extend `BasePlatformTestCase` |

---

## Common feature-add recipes

### Add a new sidecar endpoint + call it from Kotlin

1. **Python side** (`src/main/resources/sidecar/worker.py`):
   ```python
   @app.post("/psu/configure")
   def psu_configure(voltage: float, current: float) -> dict:
       # TODO: drive real PSU
       return {"ok": True, "voltage": voltage, "current": current}
   ```

2. **Kotlin side** (`service/RunnerClient.kt`):
   ```kotlin
   fun setPsu(voltage: Double, current: Double): Result {
       val url = settings.runnerUrl.trimEnd('/') + "/psu/configure"
       val body = """{"voltage":$voltage,"current":$current}"""
           .toRequestBody("application/json".toMediaType())
       val request = Request.Builder().url(url).post(body).build()
       // … same try/catch pattern as ping() …
   }
   ```

3. **Restart the sandbox** (`./gradlew runIde`) — the sidecar is re-extracted
   from the plugin JAR each time the plugin reloads.

### Add a new Python dependency

1. Edit `src/main/resources/sidecar/requirements.txt` — add the line
2. Delete `~/.scopecreep/venv/` on your laptop (forces a fresh venv on next run)
3. `./gradlew runIde`
4. Watch the IDE log (`Help → Show Log in Files`) for the pip install

### Add a new UI element to the tool window

Edit `ScopecreepPanel` in `ScopecreepToolWindowFactory.kt`. Swing + Kotlin UI
DSL. For anything bigger than a few widgets, split into its own file under
`src/main/kotlin/ai/galois/scopecreep/ui/`.

---

## Testing

### Headless (runs on the server or in CI)

```bash
./gradlew check         # tests + plugin verification
./gradlew test          # tests only
./gradlew verifyPlugin  # plugin.xml + platform compatibility
./gradlew buildPlugin   # produces build/distributions/Scopecreep-0.0.1.zip
```

These all work over SSH. Run them before opening a PR.

### GUI (laptop only)

```bash
./gradlew runIde        # sandbox IDE with plugin loaded
```

No substitute for this — always smoke-test your change in the sandbox before
asking for review.

### CI

GitHub Actions runs `build / test / verify` on every push and PR:
<https://github.com/BhavikFTW/Scopecreep/actions>

If CI is red on your branch, fix it before merging.

---

## Troubleshooting

### `./gradlew runIde` — "Unable to locate a Java Runtime"
`JAVA_HOME` not set. See [Prerequisites](#on-your-laptop-for-running-the-sandbox-ide).

### Sandbox IDE opens, but no Scopecreep tool window
- Check **View → Tool Windows** — the list should include "Scopecreep"
- If not: plugin didn't load. Check `Help → Show Log in Files` → `idea.log`
  for a stack trace and paste it in chat.

### "Ping sidecar" hangs or shows `error: Connection refused`
The sidecar didn't start. Most common causes:
- No `python3` on PATH inside the IDE's environment (macOS GUI apps don't
  always inherit your shell's PATH — try launching IntelliJ from a terminal
  with `open -a "IntelliJ IDEA.app"` or install Python via Homebrew)
- Port 8420 already in use — change it in **Settings → Tools → Scopecreep**
- Check `idea.log` for `[sidecar]` lines

### Sidecar starts but `pip install` fails
- Look in `idea.log` for the pip output
- Try running the install manually: `~/.scopecreep/venv/bin/pip install -r ~/.scopecreep/sidecar/requirements.txt`

### "Address already in use" after crash
Uvicorn didn't shut down cleanly:
```bash
pkill -f "uvicorn worker:app"
```

### Want to nuke everything and start over
```bash
rm -rf ~/.scopecreep          # sidecar + venv
rm -rf ~/.gradle/caches       # IntelliJ + all gradle deps (~2.5 GB)
rm -rf build .gradle          # this repo's build output
```
Next `runIde` will re-download everything.

---

## Disk usage reminder

After first `runIde`, expect ~2.5–3 GB of persistent cache:
- `~/.gradle/caches/` — IntelliJ, JBR, Kotlin, Gradle plugins (~2.5 GB)
- `~/.scopecreep/venv/` — Python sidecar env (~80 MB)
- `./build/` — per-repo build output (~70 MB, regenerated each build)

Clean up with:
```bash
./gradlew clean                    # repo build output only
rm -rf ~/.gradle/caches            # everything gradle — will re-download
rm -rf ~/.scopecreep               # sidecar will rebuild on next runIde
```

---

## Ship checklist (before demo)

- [ ] `main` builds green on CI
- [ ] `./gradlew runIde` on a fresh clone works for at least one teammate
- [ ] `./gradlew buildPlugin` produces a valid `.zip`
- [ ] The zip installs into a real (non-sandbox) IntelliJ
- [ ] Ping, settings round-trip, and all post-MVP features demo cleanly
- [ ] Screen recording of the demo as backup

---

*Questions? Ping in the team channel or open an issue on the repo.*
