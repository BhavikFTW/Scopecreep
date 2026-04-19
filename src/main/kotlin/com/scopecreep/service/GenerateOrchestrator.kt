package com.scopecreep.service

import com.scopecreep.settings.ScopecreepSettings
import java.io.File

/**
 * Orchestrates: parse each SchDoc via the Python sidecar, assemble a prompt,
 * and call OpenAI. Returns the LLM response or an error.
 */
class GenerateOrchestrator(
    private val runner: RunnerClient = RunnerClient(),
    private val settings: ScopecreepSettings = ScopecreepSettings.getInstance(),
) {

    data class Inputs(
        val schdocs: List<File>,
        val microcontroller: String,
        val purpose: String,
    )

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Err(val message: String) : Result()
    }

    fun run(
        inputs: Inputs,
        progress: (String) -> Unit = {},
    ): Result {
        val apiKey = ApiKeyStore.getOpenAiKey()
            ?: return Result.Err("OpenAI API key is not set. Configure it in Settings → Tools → Scopecreep.")
        if (inputs.schdocs.isEmpty()) return Result.Err("No schematic files selected.")

        val parsed = mutableListOf<Pair<String, String>>()
        for ((i, f) in inputs.schdocs.withIndex()) {
            progress("Parsing ${f.name} (${i + 1}/${inputs.schdocs.size})…")
            when (val r = runner.parseSchematic(f)) {
                is RunnerClient.Result.Ok -> parsed.add(f.name to r.body)
                is RunnerClient.Result.Err -> return Result.Err("Parse failed for ${f.name}: ${r.message}")
            }
        }

        progress("Calling OpenAI (${settings.state.openAiModel})…")
        val prompt = buildPrompt(parsed, inputs.microcontroller, inputs.purpose)
        val client = OpenAiClient(apiKey = apiKey, model = settings.state.openAiModel)
        return when (val r = client.chat(system = SYSTEM_PROMPT, user = prompt)) {
            is OpenAiClient.Result.Ok -> Result.Ok(r.text)
            is OpenAiClient.Result.Err -> Result.Err(r.message)
        }
    }

    private fun buildPrompt(
        parsed: List<Pair<String, String>>,
        mcu: String,
        purpose: String,
    ): String = buildString {
        appendLine("# Project Context")
        appendLine()
        appendLine("**Microcontroller:** ${mcu.ifBlank { "(not specified)" }}")
        appendLine()
        appendLine("**Project purpose:**")
        appendLine(purpose.ifBlank { "(not specified)" })
        appendLine()
        appendLine("# Parsed Schematics")
        for ((name, md) in parsed) {
            appendLine()
            appendLine("## Source: `$name`")
            appendLine()
            appendLine(md)
        }
    }

    companion object {
        private val SYSTEM_PROMPT = """
            You are a hardware test engineer. You are given:
            (1) natural-language context describing the microcontroller and purpose of a project,
            (2) one or more parsed schematic summaries (Markdown) extracted from Altium .SchDoc files.
            Produce a hardware test plan in Markdown with concrete, probe-level steps:
            - power rail checks with expected voltages and tolerances
            - signal integrity checks at named probe points
            - connector pinout verification
            - microcontroller peripheral bring-up checks specific to the stated MCU
            Cite specific nets, designators, and pins from the parsed data. Do not invent nets or
            components that aren't present. Flag missing information rather than guessing.
        """.trimIndent()
    }
}
