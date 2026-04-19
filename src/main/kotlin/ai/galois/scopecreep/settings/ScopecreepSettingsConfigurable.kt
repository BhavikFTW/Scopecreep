package com.scopecreep.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.scopecreep.service.ApiKeyStore
import javax.swing.JComponent

class ScopecreepSettingsConfigurable : Configurable {

    private val settings = ScopecreepSettings.getInstance()
    private val state = settings.state.copy()

    private val apiKeyField = JBPasswordField().apply {
        columns = 40
        text = ApiKeyStore.getOpenAiKey().orEmpty()
    }
    private val initialApiKey: String = ApiKeyStore.getOpenAiKey().orEmpty()

    private val ui by lazy {
        panel {
            group("Python sidecar") {
                row("Runner host:") {
                    textField().bindText(state::runnerHost).columns(20)
                }
                row("Runner port:") {
                    intTextField(1..65535).bindIntText(state::runnerPort).columns(6)
                }
            }
            group("OpenAI") {
                row("API key:") {
                    cell(apiKeyField)
                }
                row("Model:") {
                    textField().bindText(state::openAiModel).columns(20)
                }
                row {
                    comment(
                        "Key is stored in IntelliJ's PasswordSafe (OS keyring when available). " +
                            "Chat Completions endpoint is used.",
                    )
                }
            }
        }
    }

    override fun getDisplayName(): String = "Scopecreep"

    override fun createComponent(): JComponent = ui

    override fun isModified(): Boolean =
        state != settings.state || String(apiKeyField.password) != initialApiKey

    override fun apply() {
        settings.loadState(state.copy())
        val typed = String(apiKeyField.password)
        if (typed != initialApiKey) {
            ApiKeyStore.setOpenAiKey(typed.ifBlank { null })
        }
    }

    override fun reset() {
        state.runnerHost = settings.state.runnerHost
        state.runnerPort = settings.state.runnerPort
        state.openAiModel = settings.state.openAiModel
        apiKeyField.text = ApiKeyStore.getOpenAiKey().orEmpty()
    }
}
