package io.github.nevermore131315.llamaswapcontroller.data.llamaswap

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Chat streaming against MockWebServer only — never sends completions to the live llama-swap. */
class LlamaSwapChatClientTest {
    private lateinit var server: MockWebServer
    private lateinit var base: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(http: OkHttpClient = OkHttpClient()) = LlamaSwapChatHttpClient(baseUrl = { base }, http = http)

    private fun sse(vararg payloads: String): String = payloads.joinToString("") { "data: $it\n\n" }

    private fun streamResponse(body: String) =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)

    @Test
    fun `streams reasoning and content and stops at DONE`() =
        runBlocking {
            server.enqueue(
                streamResponse(sse(*QWEN_STREAM) + "data: {\"choices\":[{\"delta\":{\"content\":\"after\"}}]}\n\n"),
            )

            val deltas = client().streamChat(MODEL, listOf(LlamaSwapChatMessage("user", "hi"))).toList()

            assertEquals(
                listOf(
                    LlamaSwapChatDelta.Reasoning("The user"),
                    LlamaSwapChatDelta.Reasoning(" says hi."),
                    LlamaSwapChatDelta.Content("Hello"),
                    LlamaSwapChatDelta.Content("! How can I help?"),
                ),
                deltas,
            )
        }

    @Test
    fun `posts an OpenAI streaming request for the chosen model id`() =
        runBlocking {
            server.enqueue(streamResponse(sse("[DONE]")))

            client()
                .streamChat(
                    MODEL,
                    listOf(
                        LlamaSwapChatMessage("user", "hi"),
                        LlamaSwapChatMessage("assistant", "Hello"),
                        LlamaSwapChatMessage("user", "again"),
                    ),
                ).toList()

            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/v1/chat/completions", req.path)
            assertEquals("text/event-stream", req.getHeader("Accept"))
            val json = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
            assertEquals(MODEL, json["model"]!!.jsonPrimitive.content)
            assertTrue(json["stream"]!!.jsonPrimitive.boolean)
            val messages = json["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("user", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
            assertEquals("again", messages[2]["content"]!!.jsonPrimitive.content)
        }

    @Test
    fun `error frame mid-stream fails after the partial deltas`() =
        runBlocking {
            server.enqueue(
                streamResponse(
                    sse(
                        QWEN_STREAM[3],
                        """{"src":"llama-swap","error":{"message":"upstream process exited","type":"server_error","code":"internal_error"}}""",
                        "[DONE]",
                    ),
                ),
            )
            val seen = mutableListOf<LlamaSwapChatDelta>()
            try {
                client().streamChat(MODEL, emptyList()).collect { seen += it }
                fail("expected a stream error")
            } catch (e: LlamaSwapStreamException) {
                assertEquals("upstream process exited", e.message)
            }
            assertEquals(listOf(LlamaSwapChatDelta.Content("Hello")), seen)
        }

    @Test
    fun `HTTP error surfaces the envelope message`() =
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(404).setBody(
                    """{"src":"llama-swap","error":{"message":"could not find suitable inference handler for nope","type":"invalid_request_error","code":"not_found"}}""",
                ),
            )
            try {
                client().streamChat("nope", emptyList()).toList()
                fail("expected HTTP error")
            } catch (e: LlamaSwapHttpException) {
                assertEquals(404, e.code)
                assertEquals("HTTP 404: could not find suitable inference handler for nope", e.message)
            }
        }

    @Test
    fun `server closing without DONE completes normally`() =
        runBlocking {
            server.enqueue(streamResponse(sse(QWEN_STREAM[3])))
            assertEquals(listOf(LlamaSwapChatDelta.Content("Hello")), client().streamChat(MODEL, emptyList()).toList())
        }

    @Test
    fun `cancelling the collector cancels the HTTP call`() =
        runBlocking {
            // First chunk arrives, then the server stalls as a slow model would.
            val firstChunk = sse(QWEN_STREAM[3])
            server.enqueue(
                streamResponse(firstChunk + sse(QWEN_STREAM[4]))
                    .throttleBody(firstChunk.toByteArray().size.toLong(), 30, TimeUnit.SECONDS),
            )
            val canceled = CountDownLatch(1)
            val http =
                OkHttpClient
                    .Builder()
                    .eventListener(
                        object : EventListener() {
                            override fun canceled(call: Call) = canceled.countDown()
                        },
                    ).build()

            val first = withTimeout(10_000) { client(http).streamChat(MODEL, emptyList()).first() }

            assertEquals(LlamaSwapChatDelta.Content("Hello"), first)
            assertTrue("call was not cancelled", canceled.await(5, TimeUnit.SECONDS))
        }

    /**
     * NetworkOnMainThreadException can't happen on the JVM, so check the equivalent directly:
     * collecting on a single-thread "caller" dispatcher (standing in for Main) must never read
     * the response body on that thread.
     */
    @Test
    fun `body is never read on the collecting thread`() {
        server.enqueue(streamResponse(sse(*QWEN_STREAM)))
        val reads = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, FAKE_MAIN) }
        try {
            val deltas =
                runBlocking(executor.asCoroutineDispatcher()) {
                    client(recordingClient(reads)).streamChat(MODEL, emptyList()).toList()
                }
            assertEquals(4, deltas.size)
            assertTrue("no body reads recorded", reads.isNotEmpty())
            assertFalse("body read on caller thread: $reads", reads.any { it.startsWith(FAKE_MAIN) })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `rest calls never read the body on the calling thread`() {
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"a"}]}"""))
        server.enqueue(MockResponse().setBody("""{"running":[]}"""))
        val reads = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, FAKE_MAIN) }
        try {
            val models =
                runBlocking(executor.asCoroutineDispatcher()) {
                    LlamaSwapHttpClient(baseUrl = { base }, http = recordingClient(reads)).models()
                }
            assertEquals(listOf("a"), models.map { it.id })
            assertTrue(reads.isNotEmpty())
            assertFalse("body read on caller thread: $reads", reads.any { it.startsWith(FAKE_MAIN) })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `parser ignores role-only, usage and malformed chunks`() {
        assertTrue(LlamaSwapChatSse.parse(QWEN_STREAM[0]).isEmpty())
        assertTrue(LlamaSwapChatSse.parse(QWEN_STREAM[5]).isEmpty())
        assertTrue(LlamaSwapChatSse.parse("""{"choices":[]}""").isEmpty())
        assertTrue(LlamaSwapChatSse.parse("not json").isEmpty())
        assertEquals(
            listOf(LlamaSwapChatDelta.Reasoning("a"), LlamaSwapChatDelta.Content("b")),
            LlamaSwapChatSse.parse("""{"choices":[{"delta":{"reasoning_content":"a","content":"b"}}]}"""),
        )
    }

    @Test
    fun `parser reports string and object errors`() {
        try {
            LlamaSwapChatSse.parse("""{"error":"boom"}""")
            fail()
        } catch (e: LlamaSwapStreamException) {
            assertEquals("boom", e.message)
        }
        try {
            LlamaSwapChatSse.parse("""{"error":{"code":500,"message":"","type":"server_error"}}""")
            fail()
        } catch (e: LlamaSwapStreamException) {
            assertEquals("Server reported an error", e.message)
        }
    }

    /** OkHttp client whose response bodies record the name of every thread that reads them. */
    private fun recordingClient(reads: MutableList<String>): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val original = response.body
                    val recorded =
                        object : ResponseBody() {
                            private val source =
                                object : ForwardingSource(original.source()) {
                                    override fun read(
                                        sink: Buffer,
                                        byteCount: Long,
                                    ): Long {
                                        reads += Thread.currentThread().name
                                        return super.read(sink, byteCount)
                                    }
                                }.buffer()

                            override fun contentType(): MediaType? = original.contentType()

                            override fun contentLength(): Long = original.contentLength()

                            override fun source(): BufferedSource = source

                            override fun close() = original.close()
                        }
                    response.newBuilder().body(recorded).build()
                },
            ).build()

    private companion object {
        // Coroutine debug mode renames threads to "<name> @coroutine#N", hence startsWith.
        const val FAKE_MAIN = "fake-main"
        const val MODEL = "qwen-7b-q4"

        // Shape of llama-server's OpenAI-compatible stream for a thinking model, as relayed by llama-swap.
        val QWEN_STREAM =
            arrayOf(
                """{"choices":[{"finish_reason":null,"index":0,"delta":{"role":"assistant","content":null}}],"created":1761300000,"id":"chatcmpl-x1","model":"$MODEL","system_fingerprint":"b6800-d1e743f","object":"chat.completion.chunk"}""",
                """{"choices":[{"finish_reason":null,"index":0,"delta":{"reasoning_content":"The user"}}],"created":1761300000,"id":"chatcmpl-x1","model":"$MODEL","system_fingerprint":"b6800-d1e743f","object":"chat.completion.chunk"}""",
                """{"choices":[{"finish_reason":null,"index":0,"delta":{"reasoning_content":" says hi."}}],"created":1761300000,"id":"chatcmpl-x1","model":"$MODEL","system_fingerprint":"b6800-d1e743f","object":"chat.completion.chunk"}""",
                """{"choices":[{"finish_reason":null,"index":0,"delta":{"content":"Hello"}}],"created":1761300001,"id":"chatcmpl-x1","model":"$MODEL","system_fingerprint":"b6800-d1e743f","object":"chat.completion.chunk"}""",
                """{"choices":[{"finish_reason":null,"index":0,"delta":{"content":"! How can I help?"}}],"created":1761300001,"id":"chatcmpl-x1","model":"$MODEL","system_fingerprint":"b6800-d1e743f","object":"chat.completion.chunk"}""",
                """{"choices":[{"finish_reason":"stop","index":0,"delta":{}}],"created":1761300001,"id":"chatcmpl-x1","model":"$MODEL","system_fingerprint":"b6800-d1e743f","object":"chat.completion.chunk","usage":{"completion_tokens":9,"prompt_tokens":11,"total_tokens":20},"timings":{"prompt_n":11,"prompt_ms":35.2,"predicted_n":9,"predicted_ms":120.4}}""",
                "[DONE]",
            )
    }
}
