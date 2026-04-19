package com.scopecreep.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * Polls GET /agent/sessions/{id} at ~1 Hz on a pooled background thread.
 * Invokes [onUpdate] on the EDT for each snapshot and [onTerminal] once the
 * session reaches COMPLETE or FAILED (or the poller is stopped).
 *
 * Backend has no SSE; this is the documented path (see CLAUDE.md, agent/server.py).
 */
class SessionPoller(
    private val client: AgentClient,
    private val sessionId: String,
    private val onUpdate: (SessionSnapshot) -> Unit,
    private val onTerminal: (SessionSnapshot) -> Unit,
    private val intervalMs: Long = 1_000L,
) {
    private val log = thisLogger()
    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread { loop() }
    }

    fun stop() { running.set(false) }

    private fun loop() {
        var last: SessionSnapshot? = null
        while (running.get()) {
            val res = client.getSession(sessionId)
            val snapshot = when (res) {
                is AgentClient.Result.Ok -> SessionSnapshot.parse(res.body)
                is AgentClient.Result.Err -> SessionSnapshot(
                    sessionId = sessionId,
                    status = "ERROR",
                    completed = 0,
                    total = 0,
                    error = res.message,
                )
            }
            if (snapshot != last) {
                last = snapshot
                SwingUtilities.invokeLater { onUpdate(snapshot) }
            }
            if (snapshot.status in TERMINAL_STATES) {
                running.set(false)
                SwingUtilities.invokeLater { onTerminal(snapshot) }
                return
            }
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { return }
        }
    }

    companion object {
        val TERMINAL_STATES = setOf("COMPLETE", "FAILED", "ERROR")
    }
}

/** Flattened view of GET /agent/sessions/{id}. */
data class SessionSnapshot(
    val sessionId: String,
    val status: String,
    val completed: Int,
    val total: Int,
    val currentProbe: ProbePrompt? = null,
    val error: String? = null,
) {
    companion object {
        fun parse(json: String): SessionSnapshot {
            val progress = JsonFields.objectField(json, "progress").orEmpty()
            val probeObj = JsonFields.objectField(json, "current_probe")
            return SessionSnapshot(
                sessionId = JsonFields.stringField(json, "session_id").orEmpty(),
                status = JsonFields.stringField(json, "status").orEmpty().uppercase(),
                completed = JsonFields.intField(progress, "completed") ?: 0,
                total = JsonFields.intField(progress, "total") ?: 0,
                currentProbe = probeObj?.let(ProbePrompt::parse),
                error = JsonFields.stringField(json, "error"),
            )
        }
    }
}

data class ProbePrompt(
    val label: String,
    val net: String,
    val locationHint: String,
    val probeType: String,
    val instructions: String,
) {
    companion object {
        fun parse(obj: String) = ProbePrompt(
            label = JsonFields.stringField(obj, "label").orEmpty(),
            net = JsonFields.stringField(obj, "net").orEmpty(),
            locationHint = JsonFields.stringField(obj, "location_hint").orEmpty(),
            probeType = JsonFields.stringField(obj, "probe_type").orEmpty(),
            instructions = JsonFields.stringField(obj, "instructions").orEmpty(),
        )
    }
}
