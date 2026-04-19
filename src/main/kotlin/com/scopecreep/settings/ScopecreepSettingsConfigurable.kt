package com.scopecreep.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class ScopecreepSettingsConfigurable : Configurable {

    private val settings = ScopecreepSettings.getInstance()
    private val state = settings.state.copy()

    private val ui by lazy {
        panel {
            group("Sidecar") {
                row("Runner host:") {
                    textField().bindText(state::runnerHost).columns(20)
                }
                row("Runner port:") {
                    intTextField(1..65535).bindIntText(state::runnerPort).columns(6)
                }
            }
            group("OpenAI") {
                row("API key:") {
                    passwordField().bindText(state::openAiApiKey).columns(40)
                }
                row("Model:") {
                    textField().bindText(state::openAiModel).columns(20)
                }
            }
            row {
                comment(
                    "Scopecreep talks to the Python sidecar at <code>http://host:port</code>. " +
                        "Changes apply on next sidecar restart.",
                )
            }
        }
    }

    override fun getDisplayName(): String = "Scopecreep"

    override fun createComponent(): JComponent = ui

    override fun isModified(): Boolean = state != settings.state

    override fun apply() {
        settings.loadState(state.copy())
    }

    override fun reset() {
        state.runnerHost = settings.state.runnerHost
        state.runnerPort = settings.state.runnerPort
        state.openAiApiKey = settings.state.openAiApiKey
        state.openAiModel = settings.state.openAiModel
    }
}
