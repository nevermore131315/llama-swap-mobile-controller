package io.github.nevermore131315.llamaswapcontroller.data.llamaswap

import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaSwapEventParserTest {
    /** Real `GET /api/events` capture from llama-swap v265 (idle, 12 models, nothing loaded). */
    private val capture: String =
        requireNotNull(javaClass.classLoader?.getResource("llamaswap/events-v265-snapshot.txt")).readText()

    private fun parseStream(text: String): List<LlamaSwapEvent> {
        val parser = SseLineParser()
        return text.lines().mapNotNull { parser.feed(it) }.mapNotNull { LlamaSwapEventParser.parsePayload(it) }
    }

    @Test
    fun `real captured modelStatus line is decoded twice into models`() {
        val line = capture.lines().first { it.startsWith("data:") }
        val payload = line.removePrefix("data:")
        // The inner data is a JSON *string*, not an array — a single decode would not give models.
        assertTrue(payload.contains("\"data\":\"[{\\\"id\\\""))

        val event = LlamaSwapEventParser.parsePayload(payload)

        val models = (event as LlamaSwapEvent.ModelStatus).models
        assertEquals(12, models.size)
        assertEquals("qwen-7b-q4", models[0].id)
        assertEquals("Qwen 7B Q4", models[0].name)
        assertEquals(LlamaSwapState.STOPPED, models[0].state)
        assertTrue(models.none { it.isActive })
        assertEquals("coder-l", models.last().id)
    }

    @Test
    fun `full captured stream yields modelStatus then the other initial messages`() {
        val events = parseStream(capture)

        assertEquals(4, events.size)
        assertTrue(events[0] is LlamaSwapEvent.ModelStatus)
        assertEquals(
            listOf("uiConfig", "profileChanged", "inflight"),
            events.drop(1).map { (it as LlamaSwapEvent.Other).type },
        )
    }

    @Test
    fun `loading progress fields from apiModel are decoded`() {
        // Shape per llama-swap apiModel: loadingProgress/loadingMessage only while starting.
        val inner =
            """[{"id":"a","name":"A","state":"starting","loadingProgress":0.42,"loadingMessage":"loading tensors"},""" +
                """{"id":"b","name":"","state":"starting"},{"id":"c","state":"ready","uptimeMs":1234}]"""
        val payload = """{"type":"modelStatus","data":${LlamaSwapJson.encodeToString(String.serializer(), inner)}}"""

        val models = (LlamaSwapEventParser.parsePayload(payload) as LlamaSwapEvent.ModelStatus).models

        assertEquals(0.42, models[0].loadingProgress!!, 1e-9)
        assertEquals("loading tensors", models[0].loadingMessage)
        assertNull(models[1].loadingProgress)
        assertEquals("b", models[1].displayName)
        assertEquals(1234L, models[2].uptimeMs)
        assertTrue(models.all { it.isActive })
    }

    @Test
    fun `malformed payloads are skipped not thrown`() {
        assertNull(LlamaSwapEventParser.parsePayload("not json"))
        assertNull(LlamaSwapEventParser.parsePayload("""{"type":"modelStatus","data":"[{broken"}"""))
        assertEquals(
            LlamaSwapEvent.Activity(3),
            LlamaSwapEventParser.parsePayload("""{"type":"activity","data":"{\"id\":3}"}"""),
        )
    }

    @Test
    fun `sse parser joins multi-line data, strips one space, ignores comments`() {
        val parser = SseLineParser()
        val out =
            listOf(": keep-alive", "event: message", "data: {\"a\":", "data:1}", "", "", "data:x", "")
                .mapNotNull { parser.feed(it) }

        assertEquals(listOf("{\"a\":\n1}", "x"), out)
    }
}
