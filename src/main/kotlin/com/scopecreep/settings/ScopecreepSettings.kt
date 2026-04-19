package com.scopecreep.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(
    name = "com.scopecreep.settings.ScopecreepSettings",
    storages = [Storage("scopecreep.xml")],
)
class ScopecreepSettings : PersistentStateComponent<ScopecreepSettings.State> {

    data class State(
        var runnerHost: String = "127.0.0.1",
        var runnerPort: Int = 8420,
        var openAiApiKey: String = "",
        var openAiModel: String = "gpt-4o-mini",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(newState: State) {
        XmlSerializerUtil.copyBean(newState, state)
    }

    val runnerUrl: String
        get() = "http://${state.runnerHost}:${state.runnerPort}"

    companion object {
        fun getInstance(): ScopecreepSettings =
            ApplicationManager.getApplication().getService(ScopecreepSettings::class.java)
    }
}
