package com.scopecreep

import com.scopecreep.service.RunnerClient
import com.scopecreep.settings.ScopecreepSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File

class RunnerClientUploadTest : BasePlatformTestCase() {

    private lateinit var server: MockWebServer

    override fun setUp() {
        super.setUp()
        server = MockWebServer()
        server.start()
        ScopecreepSettings.getInstance().loadState(
            ScopecreepSettings.State(
                runnerHost = server.hostName,
                runnerPort = server.port,
            )
        )
    }

    override fun tearDown() {
        server.shutdown()
        super.tearDown()
    }

    fun testUploadFiles_returnsOkOnSuccess() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok","schematic":"/tmp/s.png","pcb":"/tmp/p.png"}""")
        )
        val schematic = File.createTempFile("schematic", ".png").also { it.writeText("fake") }
        val pcb = File.createTempFile("pcb", ".png").also { it.writeText("fake") }
        val result = RunnerClient().uploadFiles(schematic, pcb)
        assertTrue(result is RunnerClient.Result.Ok)
    }

    fun testUploadFiles_returnsErrOnHttpError() {
        server.enqueue(MockResponse().setResponseCode(500))
        val schematic = File.createTempFile("schematic", ".png").also { it.writeText("fake") }
        val pcb = File.createTempFile("pcb", ".png").also { it.writeText("fake") }
        val result = RunnerClient().uploadFiles(schematic, pcb)
        assertTrue(result is RunnerClient.Result.Err)
        assertEquals("HTTP 500", (result as RunnerClient.Result.Err).message)
    }

    fun testUploadFiles_returnsErrOnConnectionRefused() {
        server.shutdown()
        val schematic = File.createTempFile("schematic", ".png").also { it.writeText("fake") }
        val pcb = File.createTempFile("pcb", ".png").also { it.writeText("fake") }
        val result = RunnerClient().uploadFiles(schematic, pcb)
        assertTrue(result is RunnerClient.Result.Err)
    }
}
