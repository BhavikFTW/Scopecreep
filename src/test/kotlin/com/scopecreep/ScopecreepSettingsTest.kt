package com.scopecreep

import com.scopecreep.settings.ScopecreepSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ScopecreepSettingsTest : BasePlatformTestCase() {

    fun testRoundTrip() {
        val settings = ScopecreepSettings.getInstance()
        val original = settings.state.copy()
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

    fun testRoundTripNewFields() {
        val settings = ScopecreepSettings.getInstance()
        val original = settings.state.copy()
        try {
            settings.loadState(ScopecreepSettings.State(
                runnerHost = "127.0.0.1",
                runnerPort = 8420,
                supabaseUrl = "https://example.supabase.co",
                supabaseAnonKey = "anon-xyz",
                nebiusApiKey = "nb-abc",
                codexProvider = "nebius-fast",
            ))
            val loaded = settings.state
            assertEquals("https://example.supabase.co", loaded.supabaseUrl)
            assertEquals("anon-xyz", loaded.supabaseAnonKey)
            assertEquals("nb-abc", loaded.nebiusApiKey)
            assertEquals("nebius-fast", loaded.codexProvider)
        } finally {
            settings.loadState(original)
        }
    }
}
