package com.scopecreep.service

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AgentClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        client = AgentClient(settingsUrl = { base })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun healthReturnsOk() {
        server.enqueue(MockResponse().setBody("{\"status\":\"ok\"}"))
        val res = client.health()
        assertTrue(res is AgentClient.Result.Ok)
    }

    @Test
    fun startSessionReturnsId() {
        server.enqueue(
            MockResponse().setBody(
                "{\"session_id\":\"abc-123\",\"status\":\"planning\"}",
            ),
        )
        val res = client.startSession("{\"board_name\":\"demo\"}")
        val body = (res as AgentClient.Result.Ok).body
        assertEquals("abc-123", JsonFields.stringField(body, "session_id"))
        assertEquals("planning", JsonFields.stringField(body, "status"))
    }

    @Test
    fun pollerParsesProbeRequired() {
        val payload = """
            {
              "session_id":"s1",
              "status":"PROBE_REQUIRED",
              "progress":{"completed":1,"total":3},
              "current_probe":{
                "label":"TP5",
                "net":"VCC3V3",
                "location_hint":"near C12",
                "probe_type":"voltage",
                "instructions":"Place tip on TP5, ground on TP1"
              }
            }
        """.trimIndent()
        val snap = SessionSnapshot.parse(payload)
        assertEquals("PROBE_REQUIRED", snap.status)
        assertEquals(1, snap.completed)
        assertEquals(3, snap.total)
        assertNotNull(snap.currentProbe)
        assertEquals("TP5", snap.currentProbe!!.label)
        assertEquals("VCC3V3", snap.currentProbe!!.net)
        assertTrue(snap.currentProbe!!.instructions.contains("ground"))
    }

    @Test
    fun errorResponseSurfacesHttpCode() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val res = client.getSession("missing")
        assertTrue(res is AgentClient.Result.Err)
        assertTrue((res as AgentClient.Result.Err).message.contains("404"))
    }
}
