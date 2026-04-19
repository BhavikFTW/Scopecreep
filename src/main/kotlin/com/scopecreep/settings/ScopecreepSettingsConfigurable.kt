package com.scopecreep.settings

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.Configurable
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.scopecreep.service.CodexProviderManager
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
            group("Supabase (memory layer)") {
                row("Project URL:") {
                    textField()
                        .bindText({ state.supabaseUrl.orEmpty() }, { state.supabaseUrl = it })
                        .columns(40)
                }
                row("Anon key:") {
                    passwordField()
                        .bindText(
                            { state.supabaseAnonKey.orEmpty() },
                            { state.supabaseAnonKey = it }
                        )
                        .columns(40)
                }
            }
            group("Nebius (research flow)") {
                row("API key:") {
                    passwordField()
                        .bindText(
                            { state.nebiusApiKey.orEmpty() },
                            { state.nebiusApiKey = it }
                        )
                        .columns(40)
                }
                row("Codex provider:") {
                    comboBox(listOf(
                        "openai", "nebius-fast", "nebius-balanced", "nebius-precise"
                    )).bindItem(state::codexProvider.toNullableProperty())
                }
            }
            row {
                comment(
                    "Changes to Supabase/Nebius config apply on next sidecar restart " +
                        "(close and reopen the Scopecreep tool window)."
                )
            }
        }
    }

    override fun getDisplayName(): String = "Scopecreep"

    override fun createComponent(): JComponent = ui

    override fun isModified(): Boolean = state != settings.state

    override fun apply() {
        val providerChanged = state.codexProvider != settings.state.codexProvider ||
            (state.codexProvider != "openai" &&
             state.nebiusApiKey != settings.state.nebiusApiKey)
        settings.loadState(state.copy())
        if (providerChanged) {
            try {
                CodexProviderManager.getInstance()
                    .applyProvider(state.codexProvider, state.nebiusApiKey)
            } catch (t: Throwable) {
                thisLogger().warn("Failed to apply Codex provider: ${t.message}")
            }
        }
    }

    override fun reset() {
        val current = settings.state
        state.runnerHost = current.runnerHost
        state.runnerPort = current.runnerPort
        state.supabaseUrl = current.supabaseUrl
        state.supabaseAnonKey = current.supabaseAnonKey
        state.nebiusApiKey = current.nebiusApiKey
        state.codexProvider = current.codexProvider
    }
}
