package ai.galois.scopecreep

import ai.galois.scopecreep.settings.ScopecreepSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ScopecreepSettingsTest : BasePlatformTestCase() {

    fun testRoundTrip() {
        val settings = ScopecreepSettings.getInstance()
        val original = ScopecreepSettings.State(
            runnerHost = settings.state.runnerHost,
            runnerPort = settings.state.runnerPort,
        )
        try {
            settings.loadState(ScopecreepSettings.State(runnerHost = "10.0.0.7", runnerPort = 9001))

            val loaded = settings.state
            assertEquals("10.0.0.7", loaded.runnerHost)
            assertEquals(9001, loaded.runnerPort)
            assertEquals("http://10.0.0.7:9001", settings.runnerUrl)
        } finally {
            settings.loadState(original)
        }
    }
}
