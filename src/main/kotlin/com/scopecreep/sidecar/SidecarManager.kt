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
    private var profileHandler: OSProcessHandler? = null

    @Volatile
    private var agentHandler: OSProcessHandler? = null

    private val home: Path = Paths.get(System.getProperty("user.home"), ".scopecreep")
    private val sidecarDir: Path = home.resolve("sidecar")
    private val benchyDir: Path = sidecarDir.resolve("benchy")
    private val venvDir: Path = home.resolve("venv")
    private val workerPy: Path = sidecarDir.resolve("worker.py")
    private val requirementsTxt: Path = sidecarDir.resolve("requirements.txt")
    private val benchyManifest: Path = benchyDir.resolve("benchy-manifest.txt")

    fun startIfNeeded() {
        if (!started.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                extractResources()
                ensureVenv()
                installRequirements()
                launchProfileWorker()
                launchAgentWorker()
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
        for (name in listOf("config.py", "memory.py", "research.py")) {
            copyResource("/sidecar/$name", sidecarDir.resolve(name))
        }
        extractBenchyBundle()
    }

    private fun extractBenchyBundle() {
        val manifestStream = javaClass.getResourceAsStream("/sidecar/benchy/benchy-manifest.txt")
        if (manifestStream == null) {
            log.warn("Scopecreep: no bundled benchy backend (manifest missing) — agent features disabled.")
            return
        }
        val entries = manifestStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (entries.isEmpty()) {
            log.warn("Scopecreep: benchy manifest is empty — agent features disabled.")
            return
        }

        Files.createDirectories(benchyDir)
        for (rel in entries) {
            // Manifest entries starting with "../" land outside benchy/ (used
            // to mirror backend/drivers alongside benchy/ so the CLI's
            // importlib-based DPS-150 loader can resolve repo_root/backend).
            val target = benchyDir.resolve(rel).normalize()
            Files.createDirectories(target.parent)
            // Resource paths in the JAR are flat (Gradle writes siblings of
            // benchy/ directly under sidecar/). Rewrite "../" prefixes so the
            // classloader lookup hits the right entry.
            val resourceRel = if (rel.startsWith("../")) rel.removePrefix("../")
                              else "benchy/$rel"
            copyResource("/sidecar/$resourceRel", target)
        }
        // Write manifest itself for debuggability.
        copyResource("/sidecar/benchy/benchy-manifest.txt", benchyManifest)
        log.info("Scopecreep: extracted ${entries.size} benchy backend files to $benchyDir")
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

    private fun launchProfileWorker() {
        val settings = ScopecreepSettings.getInstance().state
        val cmd = GeneralCommandLine(
            venvBin("uvicorn").toString(),
            "worker:app",
            "--host",
            settings.runnerHost,
            "--port",
            settings.runnerPort.toString(),
        ).withWorkDirectory(sidecarDir.toFile())
        if (settings.supabaseUrl.isNotBlank())
            cmd.withEnvironment("SCOPECREEP_SUPABASE_URL", settings.supabaseUrl)
        if (settings.supabaseAnonKey.isNotBlank())
            cmd.withEnvironment("SCOPECREEP_SUPABASE_ANON_KEY", settings.supabaseAnonKey)
        if (settings.nebiusApiKey.isNotBlank())
            cmd.withEnvironment("SCOPECREEP_NEBIUS_API_KEY", settings.nebiusApiKey)

        profileHandler = spawn("profile", cmd)
        log.info("Scopecreep profile sidecar started on ${settings.runnerHost}:${settings.runnerPort}")
    }

    private fun launchAgentWorker() {
        if (!Files.exists(benchyManifest)) {
            log.warn("Scopecreep: skipping agent worker — benchy backend not extracted.")
            return
        }
        val settings = ScopecreepSettings.getInstance().state
        val cmd = GeneralCommandLine(
            venvBin("uvicorn").toString(),
            "agent_worker:app",
            "--host",
            settings.runnerHost,
            "--port",
            settings.agentPort.toString(),
        ).withWorkDirectory(benchyDir.toFile())
        if (settings.openAiApiKey.isNotBlank())
            cmd.withEnvironment("OPENAI_API_KEY", settings.openAiApiKey)
        if (settings.openAiModel.isNotBlank())
            cmd.withEnvironment("OPENAI_MODEL", settings.openAiModel)
        cmd.withEnvironment("MAX_VOLTAGE", settings.maxVoltage)
        cmd.withEnvironment("MAX_CURRENT", settings.maxCurrent)
        if (settings.psuPort.isNotBlank())
            cmd.withEnvironment("PSU_PORT", settings.psuPort)

        agentHandler = spawn("agent", cmd)
        log.info("Scopecreep agent sidecar started on ${settings.runnerHost}:${settings.agentPort}")
    }

    private fun spawn(tag: String, cmd: GeneralCommandLine): OSProcessHandler {
        val proc = cmd.createProcess()
        val h = OSProcessHandler(proc, cmd.commandLineString, Charsets.UTF_8)
        h.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                log.info("[$tag] ${event.text.trimEnd()}")
            }

            override fun processTerminated(event: ProcessEvent) {
                log.info("Scopecreep $tag sidecar exited with code ${event.exitCode}")
            }
        })
        h.startNotify()
        return h
    }

    private fun run(cmd: GeneralCommandLine) {
        val process = cmd.createProcess()
        val code = process.waitFor()
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
        listOf("profile" to profileHandler, "agent" to agentHandler).forEach { (tag, h) ->
            if (h == null) return@forEach
            log.info("Shutting down Scopecreep $tag sidecar")
            val p = h.process
            p.destroy()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
        profileHandler = null
        agentHandler = null
        started.set(false)
    }

    companion object {
        fun getInstance(): SidecarManager =
            ApplicationManager.getApplication().getService(SidecarManager::class.java)
    }
}
