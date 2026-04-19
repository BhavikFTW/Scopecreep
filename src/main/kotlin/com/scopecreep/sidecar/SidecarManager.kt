package com.scopecreep.sidecar

import com.scopecreep.settings.ScopecreepSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
class SidecarManager : Disposable {

    private val log = thisLogger()
    private val started = AtomicBoolean(false)

    @Volatile
    private var handler: OSProcessHandler? = null

    private val home: Path = Paths.get(System.getProperty("user.home"), ".scopecreep")
    private val sidecarDir: Path = home.resolve("sidecar")
    private val venvDir: Path = home.resolve("venv")
    private val workerPy: Path = sidecarDir.resolve("worker.py")
    private val requirementsTxt: Path = sidecarDir.resolve("requirements.txt")

    fun startIfNeeded() {
        if (!started.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                extractResources()
                ensureVenv()
                installRequirements()
                launchUvicorn()
            } catch (t: Throwable) {
                log.warn("Scopecreep sidecar failed to start: ${t.message}", t)
                started.set(false)
            }
        }
    }

    private fun extractResources() {
        Files.createDirectories(sidecarDir)
        copyResource("/sidecar/worker.py", workerPy)
        copyResource("/sidecar/requirements.txt", requirementsTxt)
    }

    private fun copyResource(resource: String, target: Path) {
        val stream = javaClass.getResourceAsStream(resource)
            ?: throw IOException("Missing bundled resource: $resource")
        stream.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun ensureVenv() {
        if (Files.exists(venvPython())) return
        log.info("Creating Scopecreep venv at $venvDir")
        val python = systemPython()
        run(GeneralCommandLine(python, "-m", "venv", venvDir.toString()))
    }

    private fun installRequirements() {
        val pip = venvBin("pip")
        log.info("Installing sidecar requirements into $venvDir")
        run(
            GeneralCommandLine(
                pip.toString(),
                "install",
                "--disable-pip-version-check",
                "--quiet",
                "-r",
                requirementsTxt.toString(),
            ),
        )
    }

    private fun launchUvicorn() {
        val settings = ScopecreepSettings.getInstance().state
        val cmd = GeneralCommandLine(
            venvBin("uvicorn").toString(),
            "worker:app",
            "--host",
            settings.runnerHost,
            "--port",
            settings.runnerPort.toString(),
        ).withWorkDirectory(sidecarDir.toFile())

        val proc = cmd.createProcess()
        val newHandler = OSProcessHandler(proc, cmd.commandLineString, Charsets.UTF_8)
        newHandler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                log.info("[sidecar] ${event.text.trimEnd()}")
            }

            override fun processTerminated(event: ProcessEvent) {
                log.info("Scopecreep sidecar exited with code ${event.exitCode}")
                handler = null
                started.set(false)
            }
        })
        newHandler.startNotify()
        handler = newHandler
        log.info("Scopecreep sidecar started on ${settings.runnerHost}:${settings.runnerPort}")
    }

    private fun run(cmd: GeneralCommandLine) {
        val process = cmd.createProcess()
        // Drain stdout on a daemon thread; without this, pip fills the OS pipe buffer
        // (~64 KB) and blocks mid-write while waitFor() waits for exit — deadlock.
        val stdoutDrain = Thread { process.inputStream.copyTo(java.io.OutputStream.nullOutputStream()) }
        stdoutDrain.isDaemon = true
        stdoutDrain.start()
        val code = process.waitFor()
        stdoutDrain.join()
        if (code != 0) {
            val stderr = process.errorStream.bufferedReader().readText()
            throw IOException("Command failed (exit $code): ${cmd.commandLineString}\n$stderr")
        }
    }

    private fun systemPython(): String =
        sequenceOf("python3", "python")
            .firstOrNull { which(it) != null }
            ?: throw IOException("No python3 on PATH — install Python 3.11+")

    private fun which(cmd: String): Path? {
        val path = System.getenv("PATH") ?: return null
        val exts = if (SystemInfo.isWindows) listOf("", ".exe", ".bat", ".cmd") else listOf("")
        return path.split(java.io.File.pathSeparatorChar)
            .asSequence()
            .flatMap { dir -> exts.asSequence().map { ext -> Paths.get(dir, "$cmd$ext") } }
            .firstOrNull { Files.isExecutable(it) }
    }

    private fun venvBin(name: String): Path =
        if (SystemInfo.isWindows) venvDir.resolve("Scripts").resolve("$name.exe")
        else venvDir.resolve("bin").resolve(name)

    private fun venvPython(): Path = venvBin(if (SystemInfo.isWindows) "python" else "python3")

    override fun dispose() {
        val h = handler ?: return
        log.info("Shutting down Scopecreep sidecar")
        val p = h.process
        p.destroy()
        if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly()
        }
        handler = null
        started.set(false)
    }

    companion object {
        fun getInstance(): SidecarManager =
            ApplicationManager.getApplication().getService(SidecarManager::class.java)
    }
}
