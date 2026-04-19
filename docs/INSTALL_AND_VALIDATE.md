# Scopecreep — install & validate

End-to-end install, first-boot, and sanity-check guide for the JetBrains
plugin with the bundled agent backend. Start here if you're setting up a
fresh machine for demo or development.

Everything below assumes Linux. macOS/Windows should work but are not tested
on this branch.

---

## 1. Prerequisites

Install these once per machine.

| Requirement | Check | Install |
|---|---|---|
| JDK 21 (for Gradle + the IntelliJ Platform plugin) | `java -version` → `21` | Download from Adoptium or set `JAVA_HOME` to a local JDK 21 (e.g. `~/tools/jdk-21`) |
| Python 3.11+ on `PATH` (for the sidecar venv) | `python3 --version` | `apt install python3.11 python3.11-venv` |
| Git | `git --version` | `apt install git` |
| (Hardware demo) DPS-150 PSU + Analog Discovery 2 | USB enumerated: `lsusb` shows both | Digilent WaveForms runtime: https://digilent.com/shop/software/digilent-waveforms/ |
| (Optional, for Chat tab) `codex` CLI | `which codex` | https://openai.com/blog/openai-codex-cli (or any `codex exec`-compatible binary) |
| (Optional, for Firmware tab) Supabase project with anon key | — | see step 6 |

If Java can't be found on `PATH`, **always** invoke Gradle with
`JAVA_HOME=...` prefixed:

```bash
JAVA_HOME=/home/alex/tools/jdk-21 ./gradlew <task>
```

The rest of this doc omits the prefix for brevity — add it if your shell
doesn't export `JAVA_HOME` globally.

---

## 2. Clone both repos side-by-side

The plugin's Gradle build vendors Python source from the jbhack repo at
build time. The default layout expects them to be siblings:

```
$WORKSPACE/
├── jbhack/            # Python backend (agent + hardware + schdoc)
└── jbhack/Scopecreep/ # This plugin (nested inside jbhack)
```

If your layout differs, pass `-Pbenchy.backend.path=/abs/path/to/python` to
every Gradle invocation (see step 4).

```bash
git clone <jbhack-url> ~/jbhack
cd ~/jbhack/Scopecreep
git checkout feat/plugin-agent-integration
```

---

## 3. Configure the backend source path (optional)

If `python/` is not at `../python` relative to the plugin, write a
`gradle.properties` entry once:

```properties
benchy.backend.path=/absolute/path/to/jbhack/python
```

Gradle re-runs `bundleBenchyBackend` whenever `python/` changes.

---

## 4. Build & run in a sandbox IDE

```bash
cd ~/jbhack/Scopecreep
./gradlew buildPlugin verifyPlugin test   # ~1 min on a warm cache
./gradlew runIde                           # launches a sandbox IDEA
```

First run downloads IntelliJ 2025.2 (~1 GB) into Gradle's caches — expect a
one-time delay. Subsequent launches take ~10s.

**Expected on sandbox IDE startup:**

1. A **Scopecreep** tool window appears in the right sidebar.
2. `~/.scopecreep/sidecar/` is populated with `worker.py` + `benchy/`.
3. `~/.scopecreep/venv/` is created and pip installs
   `sidecar/requirements.txt` (~2–4 min on cold cache; watch the IDE
   "Background Tasks" panel).
4. Two uvicorn processes launch:
   - `worker:app` on `127.0.0.1:8420` (memory/profile)
   - `agent_worker:app` on `127.0.0.1:8000` (agent + hardware)

Verify both are listening from a terminal:

```bash
curl -fsS http://127.0.0.1:8420/health
curl -fsS http://127.0.0.1:8000/health
```

If either fails, check the IDE's log panel (`Help → Show Log in Files`) for
lines prefixed `[profile]` or `[agent]`.

---

## 5. Configure credentials

Open **Settings → Tools → Scopecreep** in the sandbox IDE.

| Group | Field | When needed |
|---|---|---|
| Sidecar | Runner host | If backend is not on `127.0.0.1` (not this branch) |
| Sidecar | Profile/memory port | Change only if `8420` collides |
| Sidecar | Agent backend port | Change only if `8000` collides |
| Sidecar | Anthropic API key | Required for the agent tool-use loop (claude-sonnet/opus) |
| Supabase | Project URL + Anon key | Required for Profiles and Firmware tabs |
| Nebius | API key | Optional — only for Profiles research / Nebius-backed Codex |
| OpenAI | API key + model | Optional — schematic feature and OpenAI-backed Codex |

After editing, **fully quit the sandbox IDE and relaunch** —
`SidecarManager` forwards env vars at process spawn, not at settings save.

---

## 6. (Optional) Apply the Supabase schema

Only needed for the Profiles and Firmware tabs.

```bash
cd ~/jbhack/Scopecreep/supabase
# Using the Supabase CLI:
supabase db push

# Or paste migrations/001_init.sql and 002_firmware_jobs.sql
# into the SQL editor in the Supabase dashboard.
```

Then in the dashboard, **Database → Replication**, enable Realtime on the
`profiles` and `firmware_jobs` tables (not strictly required — plugin
polls — but nice if you want live updates).

---

## 7. Validation matrix

Run each step in order. The plugin is green if all five pass.

### 7.1 Ping tab

- Click **Scopecreep** tool window → **Ping** tab → **Ping sidecar**.
- **Pass criterion**: label shows `pong — {"status":"ok"}` within 2s.

### 7.2 Schematic tab

- Click **Schematic** tab → **Pick .SchDoc…** → select any `.SchDoc` file
  (`~/jbhack/Main.SchDoc` works).
- Click **Parse**.
- **Pass criterion**: a Markdown summary renders in the lower pane within
  30s, including sections for board name, power rails, probe points.
- If parsing hits the LLM "understanding" step with no OpenAI key set,
  you'll see an error in the status line — set the key in Settings and
  retry.

### 7.3 Agent tab (without hardware)

- Click **Agent** tab → **Load .SchDoc → JSON** → select the same `.SchDoc`
  as above.
- **Pass criterion**: the left text area populates with JSON containing
  `board_name`, `probe_points`.
- Click **Start session**.
- **Pass criterion**: status transitions to `PLANNING`. If hardware is not
  attached, the session will fail with a clear error inside 30s —
  that's OK for this step.

### 7.4 Agent tab (with hardware — full demo)

Prerequisites: DPS-150 PSU and Analog Discovery 2 both enumerated.

- Repeat 7.3 with a real board's `.SchDoc`.
- **Pass criteria**:
  1. Status progresses `PLANNING → PROBE_REQUIRED`.
  2. Probe prompt card shows the label, net, location hint, instructions.
  3. Place the probe. Click **Resume (probe placed)**.
  4. Status advances through `CAPTURING → EVALUATING` (repeats per probe).
  5. On `COMPLETE`, the right pane renders a Markdown report with a
     per-probe verdict table.
  6. Switch to **Waveform** tab — measurement table is populated with one
     row per probe, verdict cells coloured (green/red/amber).

### 7.5 Firmware tab

Requires Supabase anon key set.

- Click **Firmware** tab → edit the goal → **Generate**.
- **Pass criterion**: status line shows `Created job <uuid>.` and the Jobs
  dropdown gets a new entry.
- **Expected follow-up**: row stays at status `queued` forever unless the
  LangGraph pipeline in `~/benchy/pipeline/` is running against the same
  Supabase project. That's the documented integration point.

---

## 8. Package for distribution

```bash
./gradlew buildPlugin
ls build/distributions/Scopecreep-*.zip
```

Install into a non-sandbox IDE via **Settings → Plugins → gear →
Install Plugin from Disk…**, pick the zip.

The installed plugin behaves identically to the sandbox — same
`~/.scopecreep/` layout, same sidecar lifecycle.

---

## 9. Reset / troubleshoot

| Symptom | Fix |
|---|---|
| Sidecar never starts, no processes on 8420/8000 | Delete `~/.scopecreep/venv/` and relaunch — venv may be corrupt |
| `ModuleNotFoundError: api.server` in `[agent]` log | Rebuild plugin: `./gradlew clean buildPlugin` and reinstall. Manifest mismatch. |
| `pydwf` install fails on pip | Need Digilent WaveForms runtime installed (not just the Python pkg) |
| `codex: command not found` in Chat tab | Install codex CLI or use a different chat path |
| Firmware tab says "Supabase anon key is not configured" | Settings → Tools → Scopecreep → Supabase → Anon key, then relaunch |
| Port 8420 or 8000 in use | Change in Settings → Sidecar → port fields, relaunch |
| After changing API keys, behavior unchanged | You must **fully quit** the sandbox IDE — settings change at spawn time |

**Note:** the sidecar is an application-level service. Closing a project
leaves both uvicorns running; they exit only when the IDE itself shuts down.
This is intentional (one sidecar shared across projects) but differs from
the earlier MVP plan.

Full reset (wipes sidecar state, venv, profile cache):

```bash
rm -rf ~/.scopecreep
```

The next project open rebuilds everything.

---

## 10. What's verified vs. not

| Verified | How |
|---|---|
| Plugin compiles, unit tests pass | `./gradlew buildPlugin test` |
| Bundle task vendors the backend | `./gradlew bundleBenchyBackend` + inspect `build/generated/benchy-resources/sidecar/benchy/` |
| Settings round-trip | Unit test `ScopecreepSettingsTest` |
| `AgentClient` HTTP calls against a mock server | Unit test `AgentClientTest` |

| Not verified on this branch | Needed for |
|---|---|
| Two-sidecar cold boot on a fresh machine | Demo day 1 |
| Hardware E2E (PSU → scope → report) | 7.4 |
| Firmware pipeline advancing a row in Supabase | 7.5 with real pipeline |
| macOS / Windows | Cross-platform demo |

Treat section 10 as the "known unknowns" — close them before demoing.
