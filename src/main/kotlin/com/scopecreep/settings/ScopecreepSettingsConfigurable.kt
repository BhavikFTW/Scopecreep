package com.scopecreep.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import javax.swing.JComponent

class ScopecreepSettingsConfigurable : Configurable {

    private val settings = ScopecreepSettings.getInstance()
    private val state = settings.state.copy()

    private val panel: DialogPanel by lazy {
        panel {
            group("Sidecar") {
                row("Runner host:") {
                    textField().bindText(state::runnerHost).columns(20)
                }
                row("Profile/memory port:") {
                    intTextField(1..65535).bindIntText(state::runnerPort).columns(6)
                }
                row("Agent backend port:") {
                    intTextField(1..65535).bindIntText(state::agentPort).columns(6)
                }
                row("Max voltage (V):") {
                    textField().bindText(state::maxVoltage).columns(6)
                }
                row("Max current (A):") {
                    textField().bindText(state::maxCurrent).columns(6)
                }
                row("PSU serial port (blank=auto):") {
                    textField().bindText(state::psuPort).columns(20)
                }
            }
            group("Supabase (memory layer)") {
                row("Project URL:") {
                    textField().bindText(state::supabaseUrl).columns(40)
                }
                row("Anon key:") {
                    passwordField().bindText(state::supabaseAnonKey).columns(40)
                }
            }
            group("Nebius (research flow)") {
                row("API key:") {
                    passwordField().bindText(state::nebiusApiKey).columns(40)
                }
                row("Codex provider:") {
                    comboBox(listOf(
                        "openai", "nebius-fast", "nebius-balanced", "nebius-precise"
                    )).bindItem(state::codexProvider.toNullableProperty())
                }
            }
            group("OpenAI (schematic feature)") {
                row("API key:") {
                    passwordField().bindText(state::openAiApiKey).columns(40)
                }
                row("Model:") {
                    textField().bindText(state::openAiModel).columns(20)
                }
            }
            row {
                comment(
                    "Applying changes here automatically restarts the sidecar " +
                        "(~5s). Watch the Ping tab for status."
                )
            }
        }
    }

    override fun getDisplayName(): String = "Scopecreep"

    override fun createComponent(): JComponent = panel

    override fun isModified(): Boolean = panel.isModified()

    override fun apply() {
        val before = settings.state.copy()
        panel.apply()
        settings.loadState(state.copy())
        val after = settings.state
        val credsChanged = before.supabaseUrl != after.supabaseUrl ||
            before.supabaseAnonKey != after.supabaseAnonKey ||
            before.nebiusApiKey != after.nebiusApiKey ||
            before.openAiApiKey != after.openAiApiKey ||
            before.openAiModel != after.openAiModel ||
            before.maxVoltage != after.maxVoltage ||
            before.maxCurrent != after.maxCurrent ||
            before.psuPort != after.psuPort ||
            before.runnerHost != after.runnerHost ||
            before.runnerPort != after.runnerPort ||
            before.agentPort != after.agentPort
        if (credsChanged) {
            com.scopecreep.sidecar.SidecarManager.getInstance().restart()
        }
    }

    override fun reset() {
        val current = settings.state
        state.runnerHost = current.runnerHost
        state.runnerPort = current.runnerPort
        state.agentPort = current.agentPort
        state.supabaseUrl = current.supabaseUrl
        state.supabaseAnonKey = current.supabaseAnonKey
        state.maxVoltage = current.maxVoltage
        state.maxCurrent = current.maxCurrent
        state.psuPort = current.psuPort
        state.nebiusApiKey = current.nebiusApiKey
        state.codexProvider = current.codexProvider
        state.openAiApiKey = current.openAiApiKey
        state.openAiModel = current.openAiModel
        panel.reset()
    }
}
