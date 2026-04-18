# File Input UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the "Ping sidecar" panel with a two-file input form that POSTs a schematic file and a PCB file to the sidecar's `/upload` endpoint.

**Architecture:** Three-layer change — Python sidecar gets a new `/upload` endpoint; `RunnerClient` gets a new `uploadFiles` method using OkHttp multipart; `ScopecreepPanel` is replaced with a `GridBagLayout` form containing two file-picker rows and a Run button.

**Tech Stack:** Kotlin + OkHttp 4.12, Swing/JBPanel, Python FastAPI + `python-multipart`, OkHttp MockWebServer (test only)

---

## File Map

| File | Action |
|------|--------|
| `src/main/resources/sidecar/requirements.txt` | Add `python-multipart` |
| `src/main/resources/sidecar/worker.py` | Add `POST /upload` endpoint |
| `src/test/python/test_worker.py` | New — pytest tests for `/upload` |
| `build.gradle.kts` | Add `mockwebserver` test dependency |
| `src/main/kotlin/ai/galois/scopecreep/service/RunnerClient.kt` | Add `uploadFiles` method |
| `src/test/kotlin/ai/galois/scopecreep/RunnerClientUploadTest.kt` | New — Kotlin unit tests for `uploadFiles` |
| `src/main/kotlin/ai/galois/scopecreep/ScopecreepToolWindowFactory.kt` | Replace `ScopecreepPanel` |

---

## Task 1: Add `/upload` endpoint to the Python sidecar

**Files:**
- Modify: `src/main/resources/sidecar/requirements.txt`
- Modify: `src/main/resources/sidecar/worker.py`
- Create: `src/test/python/test_worker.py`

- [ ] **Step 1: Write the failing Python test**

Create `src/test/python/test_worker.py`:

```python
import io
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "../../main/resources/sidecar"))

from starlette.testclient import TestClient
from worker import app

client = TestClient(app)


def test_upload_returns_ok():
    response = client.post(
        "/upload",
        files={
            "schematic": ("schematic.png", io.BytesIO(b"fake schematic"), "image/png"),
            "pcb": ("pcb.png", io.BytesIO(b"fake pcb"), "image/png"),
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert "schematic" in body
    assert "pcb" in body


def test_upload_missing_file_returns_422():
    response = client.post(
        "/upload",
        files={
            "schematic": ("schematic.png", io.BytesIO(b"fake"), "image/png"),
        },
    )
    assert response.status_code == 422
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
cd /home/<you>/jbhack/Scopecreep
pip install fastapi uvicorn python-multipart pytest httpx starlette
pytest src/test/python/test_worker.py -v
```

Expected output: `FAILED test_upload_returns_ok` with a 404 or attribute error (endpoint does not exist yet).

- [ ] **Step 3: Add `python-multipart` to requirements**

Edit `src/main/resources/sidecar/requirements.txt` — replace entire file with:

```
fastapi>=0.110,<1.0
uvicorn[standard]>=0.27,<1.0
python-multipart>=0.0.9,<1.0
```

- [ ] **Step 4: Add the `/upload` endpoint to `worker.py`**

Add these imports at the top of `worker.py` (after existing imports):

```python
import shutil
import tempfile

from fastapi import File, UploadFile
```

Add the endpoint after the `/health` route:

```python
@app.post("/upload")
async def upload(
    schematic: UploadFile = File(...),
    pcb: UploadFile = File(...),
) -> dict:
    tmp_dir = tempfile.mkdtemp(prefix="scopecreep_")
    schematic_path = os.path.join(tmp_dir, schematic.filename or "schematic")
    pcb_path = os.path.join(tmp_dir, pcb.filename or "pcb")
    with open(schematic_path, "wb") as f:
        shutil.copyfileobj(schematic.file, f)
    with open(pcb_path, "wb") as f:
        shutil.copyfileobj(pcb.file, f)
    return {"status": "ok", "schematic": schematic_path, "pcb": pcb_path}
```

- [ ] **Step 5: Run the tests and confirm they pass**

```bash
pytest src/test/python/test_worker.py -v
```

Expected output:
```
PASSED test_worker.py::test_upload_returns_ok
PASSED test_worker.py::test_upload_missing_file_returns_422
```

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/sidecar/requirements.txt \
        src/main/resources/sidecar/worker.py \
        src/test/python/test_worker.py
git commit -m "feat: add /upload endpoint to sidecar"
```

---

## Task 2: Add `uploadFiles` to `RunnerClient` (Kotlin, TDD)

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/test/kotlin/ai/galois/scopecreep/RunnerClientUploadTest.kt`
- Modify: `src/main/kotlin/ai/galois/scopecreep/service/RunnerClient.kt`

- [ ] **Step 1: Add MockWebServer test dependency**

In `build.gradle.kts`, add inside the `dependencies { }` block:

```kotlin
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

- [ ] **Step 2: Write the failing Kotlin test**

Create `src/test/kotlin/ai/galois/scopecreep/RunnerClientUploadTest.kt`:

```kotlin
package ai.galois.scopecreep

import ai.galois.scopecreep.service.RunnerClient
import ai.galois.scopecreep.settings.ScopecreepSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File

class RunnerClientUploadTest : BasePlatformTestCase() {

    private lateinit var server: MockWebServer

    override fun setUp() {
        super.setUp()
        server = MockWebServer()
        server.start()
        ScopecreepSettings.getInstance().loadState(
            ScopecreepSettings.State(
                runnerHost = server.hostName,
                runnerPort = server.port,
            )
        )
    }

    override fun tearDown() {
        server.shutdown()
        super.tearDown()
    }

    fun testUploadFiles_returnsOkOnSuccess() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok","schematic":"/tmp/s.png","pcb":"/tmp/p.png"}""")
        )
        val schematic = File.createTempFile("schematic", ".png").also { it.writeText("fake") }
        val pcb = File.createTempFile("pcb", ".png").also { it.writeText("fake") }
        val result = RunnerClient().uploadFiles(schematic, pcb)
        assertTrue(result is RunnerClient.Result.Ok)
    }

    fun testUploadFiles_returnsErrOnHttpError() {
        server.enqueue(MockResponse().setResponseCode(500))
        val schematic = File.createTempFile("schematic", ".png").also { it.writeText("fake") }
        val pcb = File.createTempFile("pcb", ".png").also { it.writeText("fake") }
        val result = RunnerClient().uploadFiles(schematic, pcb)
        assertTrue(result is RunnerClient.Result.Err)
        assertEquals("HTTP 500", (result as RunnerClient.Result.Err).message)
    }

    fun testUploadFiles_returnsErrOnConnectionRefused() {
        server.shutdown()
        val schematic = File.createTempFile("schematic", ".png").also { it.writeText("fake") }
        val pcb = File.createTempFile("pcb", ".png").also { it.writeText("fake") }
        val result = RunnerClient().uploadFiles(schematic, pcb)
        assertTrue(result is RunnerClient.Result.Err)
    }
}
```

- [ ] **Step 3: Run the test to confirm it fails**

```bash
./gradlew test --tests "ai.galois.scopecreep.RunnerClientUploadTest" 2>&1 | tail -20
```

Expected: compile error — `uploadFiles` does not exist on `RunnerClient`.

- [ ] **Step 4: Implement `uploadFiles` in `RunnerClient.kt`**

Replace the entire content of `src/main/kotlin/ai/galois/scopecreep/service/RunnerClient.kt` with:

```kotlin
package ai.galois.scopecreep.service

import ai.galois.scopecreep.settings.ScopecreepSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class RunnerClient(
    private val client: OkHttpClient = defaultClient,
    private val settings: ScopecreepSettings = ScopecreepSettings.getInstance(),
) {

    fun ping(): Result {
        val url = settings.runnerUrl.trimEnd('/') + "/health"
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.Ok(response.body?.string().orEmpty())
                } else {
                    Result.Err("HTTP ${response.code}")
                }
            }
        } catch (t: Throwable) {
            Result.Err(t.message ?: t.javaClass.simpleName)
        }
    }

    fun uploadFiles(schematic: File, pcb: File): Result {
        val url = settings.runnerUrl.trimEnd('/') + "/upload"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("schematic", schematic.name, schematic.asRequestBody("application/octet-stream".toMediaType()))
            .addFormDataPart("pcb", pcb.name, pcb.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = Request.Builder().url(url).post(body).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.Ok(response.body?.string().orEmpty())
                } else {
                    Result.Err("HTTP ${response.code}")
                }
            }
        } catch (t: Throwable) {
            Result.Err(t.message ?: t.javaClass.simpleName)
        }
    }

    sealed class Result {
        data class Ok(val body: String) : Result()
        data class Err(val message: String) : Result()
    }

    companion object {
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
```

- [ ] **Step 5: Run the tests and confirm they pass**

```bash
./gradlew test --tests "ai.galois.scopecreep.RunnerClientUploadTest" 2>&1 | tail -20
```

Expected:
```
RunnerClientUploadTest > testUploadFiles_returnsOkOnSuccess PASSED
RunnerClientUploadTest > testUploadFiles_returnsErrOnHttpError PASSED
RunnerClientUploadTest > testUploadFiles_returnsErrOnConnectionRefused PASSED
```

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts \
        src/main/kotlin/ai/galois/scopecreep/service/RunnerClient.kt \
        src/test/kotlin/ai/galois/scopecreep/RunnerClientUploadTest.kt
git commit -m "feat: add uploadFiles to RunnerClient with multipart POST"
```

---

## Task 3: Replace ScopecreepPanel with file-input UI

**Files:**
- Modify: `src/main/kotlin/ai/galois/scopecreep/ScopecreepToolWindowFactory.kt`

- [ ] **Step 1: Replace the file with the new panel**

Replace the entire content of `src/main/kotlin/ai/galois/scopecreep/ScopecreepToolWindowFactory.kt`:

```kotlin
package ai.galois.scopecreep

import ai.galois.scopecreep.service.RunnerClient
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

class ScopecreepToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ScopecreepPanel()
        val content = ContentFactory.getInstance().createContent(panel.root, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

class ScopecreepPanel {

    private val schematicField = JTextField(30).apply { isEditable = false }
    private val pcbField = JTextField(30).apply { isEditable = false }
    private val schematicBrowse = JButton("Browse…")
    private val pcbBrowse = JButton("Browse…")
    private val runButton = JButton("Run").apply { isEnabled = false }
    private val statusLabel = JBLabel("")
    private val client = RunnerClient()

    val root: JBPanel<JBPanel<*>> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = JBUI.Borders.empty(8)
        add(buildForm(), BorderLayout.CENTER)
        add(buildSouth(), BorderLayout.SOUTH)
    }

    init {
        schematicBrowse.addActionListener {
            browseFile(schematicField, "Schematic files", "png", "schdoc")
        }
        pcbBrowse.addActionListener {
            browseFile(pcbField, "PCB files", "png", "pcbdoc")
        }
        runButton.addActionListener { upload() }
    }

    private fun buildForm(): JPanel {
        val form = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply { insets = Insets(4, 4, 4, 4) }

        gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        form.add(JBLabel("Schematic"), gbc)

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        form.add(schematicField, gbc)

        gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        form.add(schematicBrowse, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        form.add(JBLabel("PCB"), gbc)

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        form.add(pcbField, gbc)

        gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        form.add(pcbBrowse, gbc)

        return form
    }

    private fun buildSouth(): JPanel {
        val south = JPanel(BorderLayout())
        south.add(statusLabel, BorderLayout.WEST)
        south.add(runButton, BorderLayout.EAST)
        return south
    }

    private fun browseFile(field: JTextField, description: String, vararg extensions: String) {
        val chooser = JFileChooser()
        chooser.fileFilter = FileNameExtensionFilter(description, *extensions)
        if (chooser.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
            field.text = chooser.selectedFile.absolutePath
            runButton.isEnabled = schematicField.text.isNotBlank() && pcbField.text.isNotBlank()
        }
    }

    private fun upload() {
        runButton.isEnabled = false
        runButton.text = "Sending…"
        statusLabel.text = ""
        val schematic = File(schematicField.text)
        val pcb = File(pcbField.text)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = client.uploadFiles(schematic, pcb)
            SwingUtilities.invokeLater {
                statusLabel.text = when (result) {
                    is RunnerClient.Result.Ok -> "Uploaded."
                    is RunnerClient.Result.Err -> "error: ${result.message}"
                }
                runButton.text = "Run"
                runButton.isEnabled = true
            }
        }
    }
}
```

- [ ] **Step 2: Run the full test suite**

```bash
./gradlew check 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL` with all tests passing.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/ai/galois/scopecreep/ScopecreepToolWindowFactory.kt
git commit -m "feat: replace ping UI with schematic/PCB file input panel"
```

---

## Smoke Test (laptop only)

After all tasks are committed, pull the branch on your laptop and run:

```bash
./gradlew runIde
```

1. Open any project in the sandbox IDE
2. Open the **Scopecreep** tool window
3. Confirm you see two rows: **Schematic** and **PCB**, each with a text field and "Browse…" button
4. Confirm **Run** is disabled until both files are selected
5. Click Browse for Schematic → confirm the file picker only shows `.png` and `.schdoc` files
6. Click Browse for PCB → confirm the file picker only shows `.png` and `.pcbdoc` files
7. Select a file for each → confirm **Run** enables
8. Click **Run** → confirm button shows "Sending…" while in flight, then resets
