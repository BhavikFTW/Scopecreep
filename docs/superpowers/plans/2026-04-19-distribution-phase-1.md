# Distribution Phase 1 — One-zip GitHub Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Scopecreep as a single GitHub Release zip — no second-repo clone, no manual co-location. Phase 1 of `2026-04-19-distribution-and-backend-orchestration-design.md`.

**Architecture:** Add the Python backend (`Kenneth-Ross/Scopecreep-hardware`) as a git submodule under `backend/`. Update the `bundleBenchyBackend` Gradle task so its default source path is `backend/python` and so it **fails loudly** when the backend is absent (instead of silently writing a `SystemExit` stub). Add a tag-triggered GitHub Actions workflow that builds the zip with submodules checked out and attaches it to a GitHub Release. Update the existing `build.yml` to check out submodules recursively so nightly artifacts are real too.

**Tech Stack:** Gradle (Kotlin DSL), IntelliJ Platform plugin, GitHub Actions (`actions/checkout@v6`, `actions/setup-java@v5`, `gradle/actions/setup-gradle@v5`, `softprops/action-gh-release@v2`), git submodules.

---

## File Structure

| File | Role |
|---|---|
| `build.gradle.kts` | Harden `bundleBenchyBackend`: fail on missing backend; change default source to `backend/python`. |
| `.gitmodules` | **NEW** — pins `backend` → `Scopecreep-hardware`. |
| `backend/` | **NEW** — submodule directory, committed as a gitlink. |
| `.github/workflows/build.yml` | Add `submodules: recursive` to both `actions/checkout@v6` invocations that run builds so the nightly zip contains the real backend. |
| `.github/workflows/release.yml` | **NEW** — tag-triggered build that attaches the zip to a GitHub Release. |
| `README.md` | Replace source-only install instructions with the Releases flow + a small contributor subsection. |

No Kotlin source or Python source changes in Phase 1.

---

## Task 1: Harden `bundleBenchyBackend` — fail loudly when backend is missing

**Why first:** this is the regression-prevention step. Today the task silently writes a `SystemExit` placeholder when the backend source is absent, which is exactly the bug the teammate hit on macOS. We make that a hard build failure before we touch anything else, so every subsequent task either succeeds with a real backend or fails at build time (never at IDE-launch time).

**Files:**
- Modify: `build.gradle.kts:40-68` (the `bundleBenchyBackend` task).

- [ ] **Step 1: Baseline the current stub behavior**

Run the existing bundler against a nonexistent path and observe the "empty bundle" warning + stub placeholder:

```bash
./gradlew --console=plain bundleBenchyBackend -Pbenchy.backend.path=/tmp/does-not-exist 2>&1 | tail -10
ls build/generated/benchy-resources/sidecar/benchy/
head -2 build/generated/benchy-resources/sidecar/benchy/agent_worker.py
```

Expected: the task **succeeds** (this is the bug). The `agent_worker.py` first line is `# Placeholder — benchy backend was not bundled at build time.`.

- [ ] **Step 2: Replace the silent-stub branch with a hard failure**

Edit `build.gradle.kts`. In the `doLast { … }` block of `bundleBenchyBackend`, replace the entire existing "source missing" branch:

```kotlin
if (!srcDir.exists()) {
    logger.warn("bundleBenchyBackend: source $srcDir not found — emitting empty bundle.")
    outDir.resolve("benchy-manifest.txt").writeText("")
    outDir.resolve("agent_worker.py").writeText(
        "# Placeholder — benchy backend was not bundled at build time.\n" +
            "raise SystemExit('benchy backend not bundled; set -Pbenchy.backend.path=/abs/to/python')\n"
    )
    return@doLast
}
```

with this hard-failure version:

```kotlin
if (!srcDir.exists()) {
    throw GradleException(
        """
        |bundleBenchyBackend: backend source not found at $srcDir.
        |
        |If you cloned this repo without submodules, run:
        |    git submodule update --init --recursive
        |
        |To point the bundler at a checkout outside the submodule, rebuild with:
        |    ./gradlew buildPlugin -Pbenchy.backend.path=/absolute/path/to/python
        """.trimMargin()
    )
}
```

- [ ] **Step 3: Verify the task now fails when backend is missing**

Run the same command as Step 1:

```bash
./gradlew --console=plain bundleBenchyBackend -Pbenchy.backend.path=/tmp/does-not-exist 2>&1 | tail -20
```

Expected: **BUILD FAILED**, with the error text containing `backend source not found at /tmp/does-not-exist` and the two suggested commands.

- [ ] **Step 4: Verify the task still succeeds with the real backend**

The existing on-disk backend at `../python` (the peer checkout at `/home/alex/jbhack/python`) should still work unchanged because the default `benchy.backend.path` is still `../python` in this task:

```bash
./gradlew --console=plain bundleBenchyBackend 2>&1 | tail -5
head -2 build/generated/benchy-resources/sidecar/benchy/agent_worker.py
```

Expected: BUILD SUCCESSFUL. First line of `agent_worker.py` is `# Generated by Scopecreep bundleBenchyBackend.` (the real shim, not the placeholder).

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts
git commit -m "build: fail loudly when benchy backend source is missing

Previously bundleBenchyBackend silently wrote a SystemExit stub when
the backend source directory didn't exist. That produced zips that
installed cleanly but crashed the agent worker at runtime, surfacing
only as 'Error connecting to server' in the UI.

Now the task fails the build with a clear pointer at the two fixes
(recurse submodules, or override with -Pbenchy.backend.path=...).
Required prep for the submodule + release pipeline landing next."
```

---

## Task 2: Add `Scopecreep-hardware` as a submodule under `backend/`

**Files:**
- Create: `.gitmodules`
- Create: `backend/` (gitlink)

- [ ] **Step 1: Add the submodule pinned to `Scopecreep-hardware/main`**

```bash
git submodule add https://github.com/Kenneth-Ross/Scopecreep-hardware.git backend
git submodule update --init --recursive
```

- [ ] **Step 2: Confirm the expected structure**

```bash
ls backend/python/api/server.py
cat .gitmodules
```

Expected: `backend/python/api/server.py` exists. `.gitmodules` contains a `[submodule "backend"]` block with `path = backend` and `url = https://github.com/Kenneth-Ross/Scopecreep-hardware.git`.

- [ ] **Step 3: Verify the bundler sees the submodule path**

```bash
./gradlew --console=plain bundleBenchyBackend -Pbenchy.backend.path=backend/python 2>&1 | tail -5
head -2 build/generated/benchy-resources/sidecar/benchy/agent_worker.py
```

Expected: BUILD SUCCESSFUL; `agent_worker.py` first line is `# Generated by Scopecreep bundleBenchyBackend.`.

- [ ] **Step 4: Commit the submodule pointer**

```bash
git add .gitmodules backend
git commit -m "build: vendor Scopecreep-hardware as git submodule at backend/

Pins the Python backend so a single 'git clone --recurse-submodules'
gives a buildable checkout, and so CI can produce a working zip
without needing two repos co-located by hand."
```

---

## Task 3: Change the default `benchy.backend.path` to `backend/python`

**Why now (not earlier):** the default only changes after the submodule exists, otherwise plain `./gradlew buildPlugin` breaks for anyone mid-bisect.

**Files:**
- Modify: `build.gradle.kts:40-41`.

- [ ] **Step 1: Edit the default property value**

Change:

```kotlin
val benchyBackendSrc: String =
    (project.findProperty("benchy.backend.path") as String?) ?: "../python"
```

to:

```kotlin
val benchyBackendSrc: String =
    (project.findProperty("benchy.backend.path") as String?) ?: "backend/python"
```

- [ ] **Step 2: Verify the default path resolves**

```bash
./gradlew --console=plain bundleBenchyBackend 2>&1 | tail -5
head -2 build/generated/benchy-resources/sidecar/benchy/agent_worker.py
```

Expected: BUILD SUCCESSFUL; real shim in `agent_worker.py`.

- [ ] **Step 3: Verify the override still works**

```bash
./gradlew --console=plain bundleBenchyBackend -Pbenchy.backend.path=backend/python 2>&1 | tail -5
./gradlew --console=plain bundleBenchyBackend -Pbenchy.backend.path=/tmp/does-not-exist 2>&1 | tail -10
```

Expected: first invocation succeeds; second fails with the error message from Task 1.

- [ ] **Step 4: Full plugin build smoke-test**

```bash
./gradlew --console=plain buildPlugin 2>&1 | tail -10
unzip -p build/distributions/Scopecreep-*.zip "*/lib/Scopecreep-*.jar" > /tmp/scp.jar
unzip -p /tmp/scp.jar sidecar/benchy/agent_worker.py | head -2
```

Expected: BUILD SUCCESSFUL; the agent_worker.py inside the jar is the real shim.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts
git commit -m "build: default benchy.backend.path to backend/python

Now that the Python backend is vendored as a submodule at backend/,
a plain './gradlew buildPlugin' produces a working zip. The
-Pbenchy.backend.path override still works for devs iterating on a
checkout of Scopecreep-hardware outside the submodule."
```

---

## Task 4: Update existing `build.yml` to recurse submodules

**Why:** without `submodules: recursive`, the existing nightly CI would hit the new hard failure from Task 1. Nightly artifacts must also be real zips.

**Files:**
- Modify: `.github/workflows/build.yml:27-28`, `.github/workflows/build.yml:68-69`, `.github/workflows/build.yml:103-104` (every `actions/checkout@v6` used by a job that runs Gradle).

- [ ] **Step 1: Add `submodules: recursive` to the three checkout steps**

For each of the three `actions/checkout@v6` steps in `build.yml` (in jobs `build`, `test`, `verify`), change:

```yaml
- name: Fetch Sources
  uses: actions/checkout@v6
```

to:

```yaml
- name: Fetch Sources
  uses: actions/checkout@v6
  with:
    submodules: recursive
```

- [ ] **Step 2: Sanity-check locally that Gradle still works without GitHub context**

```bash
./gradlew --console=plain buildPlugin 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL (no CI run yet — we're only verifying we didn't break the local build with the yaml edit).

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/build.yml
git commit -m "ci(build): checkout backend submodule so nightly artifacts are real

Task 1 makes bundleBenchyBackend a hard failure when backend/ is
empty. Every CI job that runs Gradle now needs submodules:recursive
on its checkout or the build explodes before producing an artifact."
```

---

## Task 5: Add `release.yml` — tag-triggered GitHub Release with zip attached

**Files:**
- Create: `.github/workflows/release.yml`.

- [ ] **Step 1: Write the workflow**

Create `.github/workflows/release.yml`:

```yaml
# Cut a GitHub Release with the plugin zip attached.
#
# Triggers:
#   push of a tag matching v*   → real release, zip uploaded via softprops/action-gh-release
#   workflow_dispatch           → manual re-run (uploads artifact only, no release created)

name: Release
on:
  push:
    tags:
      - 'v*'
  workflow_dispatch:

concurrency:
  group: release-${{ github.ref }}
  cancel-in-progress: false

permissions:
  contents: write

jobs:
  release:
    name: Build and publish zip
    runs-on: ubuntu-latest
    steps:
      - name: Maximize Build Space
        uses: jlumbroso/free-disk-space@v1.3.1
        with:
          tool-cache: false
          large-packages: false

      - name: Fetch Sources
        uses: actions/checkout@v6
        with:
          submodules: recursive
          fetch-depth: 0

      - name: Setup Java
        uses: actions/setup-java@v5
        with:
          distribution: zulu
          java-version: 21

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v5

      - name: Build plugin
        run: ./gradlew --no-daemon buildPlugin

      - name: Locate zip
        id: zip
        shell: bash
        run: |
          set -euo pipefail
          ZIP_PATH=$(ls build/distributions/Scopecreep-*.zip | head -n 1)
          echo "path=$ZIP_PATH" >> "$GITHUB_OUTPUT"
          echo "name=$(basename "$ZIP_PATH")" >> "$GITHUB_OUTPUT"

      - name: Upload artifact (always)
        uses: actions/upload-artifact@v6
        with:
          name: ${{ steps.zip.outputs.name }}
          path: ${{ steps.zip.outputs.path }}
          retention-days: 30

      - name: Attach to GitHub Release (tag only)
        if: startsWith(github.ref, 'refs/tags/v')
        uses: softprops/action-gh-release@v2
        with:
          files: ${{ steps.zip.outputs.path }}
          generate_release_notes: true
          fail_on_unmatched_files: true
```

- [ ] **Step 2: Local YAML sanity check**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml'))"
```

Expected: exits 0 (no YAML syntax errors). If `python3` is not available, use `ruby -ryaml -e "YAML.load_file('.github/workflows/release.yml')"` as a fallback.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci(release): tag-triggered GitHub Release with plugin zip

Pushing a v* tag builds the plugin with submodules checked out and
attaches the resulting Scopecreep-<ver>.zip to an auto-generated
Release. workflow_dispatch supports manual re-runs (artifact only,
no Release created).

softprops/action-gh-release handles the Release creation; we pass
generate_release_notes:true so the Release body auto-fills from
commit diffs between the new tag and the previous tag."
```

---

## Task 6: README — replace source-only install with Releases flow

**Files:**
- Modify: `README.md`.

- [ ] **Step 1: Read the current README top section**

```bash
head -80 README.md
```

Confirm the layout — the current file has a `## Status` table followed by `## Requirements` and `## Run from source`. We insert a new `## Install (users)` section between `## Status` and `## Requirements`, and tighten `## Run from source` into a `## Build from source (contributors)` section.

- [ ] **Step 2: Insert the new install section**

Immediately after the `## Status` table (before `## Requirements`), insert:

```markdown
## Install

The plugin is distributed as a single zip on the **[Releases page](https://github.com/BhavikFTW/Scopecreep/releases)** — you do **not** need to clone this repo or run Gradle to use it.

1. Download the latest `Scopecreep-x.y.z.zip`.
2. In your JetBrains IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the zip, restart the IDE.
3. On first project open, the plugin bootstraps a local Python venv at `~/.scopecreep/venv/` and launches two uvicorn workers (`:8420` for profiles, `:8000` for the agent backend). First run takes 30–90 s; progress and errors surface in the **Schematic Testbench** tab.

Requires `python3.11+` on `PATH` and a JetBrains IDE 2025.2 or newer.

```

- [ ] **Step 3: Rename and shorten the `Run from source` section**

Change the heading `## Run from source` to `## Build from source (contributors)` and replace the first code block:

```bash
./gradlew runIde
```

with:

```bash
git clone --recurse-submodules https://github.com/BhavikFTW/Scopecreep
cd Scopecreep
./gradlew runIde         # sandbox IDE for iteration
./gradlew buildPlugin    # produce build/distributions/Scopecreep-*.zip
```

Add a short paragraph after that code block:

```markdown
The Python backend is a git submodule at `backend/`. Always clone with `--recurse-submodules`, or run `git submodule update --init --recursive` after cloning. To build against a local checkout of `Scopecreep-hardware` outside the submodule, pass `-Pbenchy.backend.path=/absolute/path/to/python` to any Gradle invocation.
```

- [ ] **Step 4: Sanity-check the diff**

```bash
git diff README.md | head -80
```

Confirm: the changes are contained within a new `## Install` block and the renamed `## Build from source (contributors)` block. No other sections touched.

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs(readme): install from Releases zip; contributors use --recurse-submodules

Point end users at the GitHub Releases zip (no git/gradle needed).
Contributor build instructions now call out the backend submodule
explicitly so a fresh clone without --recurse-submodules is caught
early rather than producing a silent-stub zip."
```

---

## Task 7: End-to-end verification — cut a release candidate tag

This task is manual verification, not additional code.

- [ ] **Step 1: Push all Task 1-6 commits**

```bash
git push
```

- [ ] **Step 2: Confirm nightly `build.yml` succeeded with submodule**

Open `https://github.com/BhavikFTW/Scopecreep/actions` in a browser (or `gh run list --workflow=build.yml --limit 3`). Latest run on your branch should be green.

- [ ] **Step 3: Tag and push a release candidate**

```bash
git tag v0.1.0-rc1
git push --tags
```

- [ ] **Step 4: Watch the release workflow**

```bash
gh run watch --workflow=release.yml
```

or visit the Actions tab. Expected: job `release` runs to completion (~3 min).

- [ ] **Step 5: Confirm the Release exists with the zip attached**

```bash
gh release view v0.1.0-rc1
gh release download v0.1.0-rc1 --pattern 'Scopecreep-*.zip' --output /tmp/scopecreep-rc.zip
unzip -p /tmp/scopecreep-rc.zip "Scopecreep/lib/Scopecreep-*.jar" > /tmp/rc-plugin.jar
unzip -p /tmp/rc-plugin.jar sidecar/benchy/agent_worker.py | head -2
```

Expected: first line is `# Generated by Scopecreep bundleBenchyBackend.` (real shim, not placeholder). If it says "Placeholder — benchy backend was not bundled", the submodule wasn't checked out by CI — re-read Task 5 and Task 4 for the `submodules: recursive` fix.

- [ ] **Step 6: Install the RC zip on a Mac (or a second machine)**

1. Download the zip from the Releases page.
2. IDE → Settings → Plugins → gear → Install Plugin from Disk.
3. Restart IDE, open any project.
4. Within ~60s, `curl http://127.0.0.1:8000/health` answers with a JSON body.
5. Schematic Testbench → pick a `.SchDoc` → Parse → Markdown summary renders (proves end-to-end the agent worker on :8000 is up).

- [ ] **Step 7: Promote to a non-RC tag if the RC worked**

```bash
git tag v0.1.0
git push --tags
```

(The same CI path runs; a new Release `v0.1.0` appears. The RC release can be deleted via `gh release delete v0.1.0-rc1` if you want a clean Release list.)

---

## Self-review notes

- **Spec coverage:** §1.1 repo layout → Task 2 + Task 3. §1.2 CI workflow → Task 5 (release) + Task 4 (existing build.yml submodule fix). §1.3 user install flow → Task 6. §1.4 contributor flow → Task 6. Verification #1–#4 in the spec → Task 7. Phase 2 is out of scope for this plan.
- **Failure-mode check:** the spec's verification #3 ("fresh clone without `--recurse-submodules` fails loudly") is guaranteed by Task 1 (gradle task throws) and enforced by Task 4 (CI uses `submodules: recursive` so CI builds never regress to stub zips).
- **Non-goals preserved:** no marketplace, no signing, no hermetic python, no worker merge.
