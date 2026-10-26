package io.github.nevermore131315.llamaswapcontroller.data.llamaswap

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Exercises the HTTP contract against MockWebServer — never the live llama-swap. */
class LlamaSwapHttpClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LlamaSwapHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        client = LlamaSwapHttpClient(baseUrl = { base }, http = OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `version parses api version`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"build_date":"2026-10-14T08:30:34Z","commit":"d1e743f","version":"v265"}""",
                ),
            )

            val v = client.version()

            assertEquals(LlamaSwapVersion("v265", "d1e743f", "2026-10-14T08:30:34Z"), v)
            assertEquals("/api/version", server.takeRequest().path)
        }

    @Test
    fun `models joins v1 models with running`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"object":"list","data":[{"id":"a","name":"Alpha","status":{"value":"loaded"}},""" +
                        """{"id":"b","object":"model"}]}""",
                ),
            )
            server.enqueue(MockResponse().setBody("""{"running":[{"model":"a","state":"ready","cmd":"x"}]}"""))

            val models = client.models()

            assertEquals(listOf("a", "b"), models.map { it.id })
            assertEquals(LlamaSwapState.READY, models[0].state)
            assertEquals(LlamaSwapState.STOPPED, models[1].state)
            assertEquals("b", models[1].displayName)
            assertEquals("/v1/models", server.takeRequest().path)
            assertEquals("/running", server.takeRequest().path)
        }

    @Test
    fun `load uses upstream GET like the llama-swap UI`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("<html/>"))

            client.load("qwen-7b-q4")

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertTrue(req.path!!, req.path!!.startsWith("/upstream/qwen-7b-q4/?_="))
        }

    @Test
    fun `load treats upstream 404 as success when the model is ready`() =
        runBlocking {
            // Real v265 + llama-server --no-webui: the proxied "/" answers 404 after the load.
            server.enqueue(
                MockResponse().setResponseCode(404).setBody(
                    """{"error":{"code":404,"message":"File Not Found","type":"not_found_error"}}""",
                ),
            )
            server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"coder-l","name":"Coder L"}]}"""))
            server.enqueue(MockResponse().setBody("""{"running":[{"model":"coder-l","state":"ready","cmd":"x"}]}"""))

            client.load("coder-l")

            assertTrue(server.takeRequest().path!!.startsWith("/upstream/coder-l/"))
            assertEquals("/v1/models", server.takeRequest().path)
            assertEquals("/running", server.takeRequest().path)
        }

    @Test
    fun `load still fails when the upstream errors and the model is not ready`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(502).setBody("upstream failed to start"))
            server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"coder-l"}]}"""))
            server.enqueue(MockResponse().setBody("""{"running":[]}"""))
            try {
                client.load("coder-l")
                fail("expected failure")
            } catch (e: LlamaSwapHttpException) {
                assertEquals(502, e.code)
            }
        }

    @Test
    fun `unload and unload all POST to the api endpoints`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("OK"))
            server.enqueue(MockResponse().setBody("""{"msg":"ok"}"""))

            client.unload("gemma-9b-q4")
            client.unloadAll()

            server.takeRequest().let {
                assertEquals("POST", it.method)
                assertEquals("/api/models/unload/gemma-9b-q4", it.path)
            }
            server.takeRequest().let {
                assertEquals("POST", it.method)
                assertEquals("/api/models/unload", it.path)
            }
        }

    @Test
    fun `http errors surface as LlamaSwapHttpException`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404).setBody("model not found"))
            try {
                client.unload("nope")
                fail("expected failure")
            } catch (e: LlamaSwapHttpException) {
                assertEquals(404, e.code)
                assertTrue(e.message!!.contains("model not found"))
            }
        }

    @Test
    fun `events stream decodes the real capture and completes when the server closes`() =
        runBlocking {
            val capture =
                requireNotNull(javaClass.classLoader?.getResource("llamaswap/events-v265-snapshot.txt")).readText()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(capture))

            val events = client.events().toList()

            assertEquals(4, events.size)
            assertEquals(12, (events[0] as LlamaSwapEvent.ModelStatus).models.size)
            assertEquals("/api/events", server.takeRequest().path)
        }

    @Test
    fun `events stream fails on http error so the caller can back off`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(502))
            val error = runCatching { client.events().first() }.exceptionOrNull()
            assertTrue(error is IOException)
        }

    @Test
    fun `activity GETs with limit and optional min_id and parses the real capture`() =
        runBlocking {
            val capture =
                requireNotNull(javaClass.classLoader?.getResource("llamaswap/activity-v265-snapshot.json")).readText()
            server.enqueue(MockResponse().setBody(capture))
            server.enqueue(MockResponse().setBody("""{"data":[],"page":1,"limit":100,"total":0}"""))

            val first = client.activity(limit = 100)
            val newer = client.activity(limit = 5000, minId = 180)

            assertEquals(499, first.data.single().respStatusCode)
            assertTrue(newer.data.isEmpty())
            val r1 = server.takeRequest()
            assertEquals("GET", r1.method)
            assertEquals("/api/metrics/activity?limit=100", r1.path)
            // limit is clamped to the server's 1..999 range.
            assertEquals("/api/metrics/activity?limit=999&min_id=180", server.takeRequest().path)
        }

    @Test
    fun `activity stats GETs api metrics stats`() =
        runBlocking {
            val capture =
                requireNotNull(javaClass.classLoader?.getResource("llamaswap/stats-v265-snapshot.json")).readText()
            server.enqueue(MockResponse().setBody(capture))

            val stats = client.activityStats()

            assertEquals(1L, stats.totalRequests)
            assertNull(stats.promptHistogram)
            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertEquals("/api/metrics/stats", req.path)
        }

    @Test
    fun `activity http error surfaces as LlamaSwapHttpException`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(400).setBody("limit must be between 1 and 999"))
            val error = runCatching { client.activity(limit = 10) }.exceptionOrNull()
            assertTrue(error is LlamaSwapHttpException)
            assertEquals(400, (error as LlamaSwapHttpException).code)
        }

    @Test
    fun `performance GETs full history then after param encoded verbatim`() =
        runBlocking {
            val capture =
                requireNotNull(
                    javaClass.classLoader?.getResource("llamaswap/performance-v265-snapshot.json"),
                ).readText()
            server.enqueue(MockResponse().setBody(capture))
            server.enqueue(MockResponse().setBody("""{"enabled":true,"gpu_stats":[],"sys_stats":[]}"""))

            val full = client.performance()
            assertTrue(full.enabled)
            assertEquals(6, full.gpuStats.size)
            val first = server.takeRequest()
            assertEquals("GET", first.method)
            assertEquals("/api/performance", first.path)

            client.performance(after = "2026-10-24T18:42:25.708780744-05:00")
            val second = server.takeRequest()
            assertEquals("GET", second.method)
            assertEquals("2026-10-24T18:42:25.708780744-05:00", second.requestUrl?.queryParameter("after"))

            // Go's url.Query() decodes a raw '+' as a space, so positive offsets must be escaped.
            server.enqueue(MockResponse().setBody("""{"enabled":true}"""))
            client.performance(after = "2026-10-24T23:42:25+02:00")
            val encoded =
                server
                    .takeRequest()
                    .requestUrl
                    ?.encodedQuery
                    .orEmpty()
            assertTrue(encoded, encoded.contains("%2B02") && !encoded.contains("+"))
        }

    @Test
    fun `performance 503 always reports disabled even with a non-json body`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"enabled":false}"""))
            assertFalse(client.performance().enabled)

            // llama-swap documents 503 as "monitoring disabled"; a future version could change the
            // body, so any 503 degrades to disabled instead of surfacing a raw error.
            server.enqueue(MockResponse().setResponseCode(503).setBody("upstream busy"))
            assertFalse(client.performance().enabled)
        }

    @Test
    fun `settings normalize accepts http urls and strips trailing slash`() {
        assertEquals("http://192.168.1.10:8080", LlamaSwapSettings.normalize(" http://192.168.1.10:8080/ "))
        assertEquals("https://x.example/ls", LlamaSwapSettings.normalize("https://x.example/ls/"))
        assertNull(LlamaSwapSettings.normalize(""))
        assertNull(LlamaSwapSettings.normalize("192.168.1.10:8080"))
        assertNull(LlamaSwapSettings.normalize("ftp://host"))
        assertFalse(LlamaSwapSettings.isConfigured(LlamaSwapSettings.DEFAULT_BASE_URL))
        assertTrue(LlamaSwapSettings.isConfigured("http://192.168.1.10:8080"))
    }
}
