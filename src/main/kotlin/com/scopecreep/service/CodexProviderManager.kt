package com.scopecreep.service

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class CodexProviderManager {

    private val log = thisLogger()
    private val scriptTarget =
        Paths.get(System.getProperty("user.home"), ".scopecreep", "codex-nebius-setup.sh")

    fun applyProvider(provider: String, nebiusApiKey: String?) {
        if (provider == "openai") {
            log.info("Codex provider: openai (no-op)")
            return
        }
        val key = nebiusApiKey?.takeIf { it.isNotBlank() }
            ?: throw IOException("Nebius API key is not set; open Settings → Tools → Scopecreep")
        extractScript()
        ApplicationManager.getApplication().executeOnPooledThread { runScript(provider, key) }
    }

    private fun extractScript() {
        Files.createDirectories(scriptTarget.parent)
        val stream = javaClass.getResourceAsStream("/scripts/codex-nebius-setup.sh")
            ?: throw IOException("bundled codex-nebius-setup.sh missing from plugin JAR")
        stream.use { Files.copy(it, scriptTarget, StandardCopyOption.REPLACE_EXISTING) }
        scriptTarget.toFile().setExecutable(true)
    }

    private fun runScript(provider: String, key: String) {
        val cmd = GeneralCommandLine("bash", scriptTarget.toString())
            .withEnvironment("NEBIUS_API_KEY", key)
            .withEnvironment("CODEX_PROFILE_DEFAULT", profileName(provider))
        val proc = cmd.createProcess()
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroy()
            log.warn("codex-nebius-setup timed out")
            return
        }
        log.info("codex-nebius-setup exited with ${proc.exitValue()}")
    }

    private fun profileName(provider: String): String = when (provider) {
        "nebius-fast" -> "nebius-fast"
        "nebius-balanced" -> "nebius-token-factory"
        "nebius-precise" -> "nebius-precise"
        else -> "nebius-token-factory"
    }

    companion object {
        fun getInstance(): CodexProviderManager =
            ApplicationManager.getApplication().getService(CodexProviderManager::class.java)
    }
}
