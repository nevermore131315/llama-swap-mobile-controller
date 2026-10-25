package io.github.nevermore131315.llamaswapcontroller.data.llamaswap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LlamaSwapActivityParsingTest {
    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("llamaswap/$name")).readText()

    private fun page(json: String) = LlamaSwapJson.decodeFromString(LlamaSwapActivityPage.serializer(), json)

    private fun stats(json: String) = LlamaSwapJson.decodeFromString(LlamaSwapActivityStats.serializer(), json)

    /** Real `GET /api/metrics/activity` from llama-swap v265: one 499 row with zero tokens. */
    @Test
    fun `real captured 499 row parses as client disconnected with no tokens`() {
        val p = page(resource("activity-v265-snapshot.json"))

        assertEquals(1, p.page)
        assertEquals(25, p.limit)
        assertEquals(1L, p.total)
        assertEquals(1, p.totalPages)
        val row = p.data.single()
        assertEquals(1L, row.id)
        assertEquals("ip:127.0.0.1", row.src)
        assertEquals("qwen-7b-q4", row.model)
        assertEquals("/v1/chat/completions", row.reqPath)
        assertEquals("", row.respContentType)
        assertEquals(499, row.respStatusCode)
        assertEquals(LlamaSwapStatusKind.CLIENT_DISCONNECTED, LlamaSwapStatusKind.of(row.respStatusCode))
        assertEquals(60_025L, row.durationMs)
        assertFalse(row.hasCapture)
        assertEquals("client disconnected before response", row.errorMsg)
        assertEquals(Instant.parse("2026-10-24T22:40:02Z").toEpochMilli(), row.epochMs)
        assertTrue(row.tokens.isEmpty)
        assertNull(row.tokens.promptSpeedOrNull)
        assertNull(row.tokens.genSpeedOrNull)
        assertNull(row.tokens.cacheTokensOrNull)
        assertNull(row.tokens.draftAcceptance)
    }

    /** Real `GET /api/metrics/stats` right after a restart: histograms are JSON null. */
    @Test
    fun `real captured stats with null histograms parse`() {
        val s = stats(resource("stats-v265-snapshot.json"))

        assertEquals(1L, s.totalRequests)
        assertEquals(0L, s.totalInputTokens)
        assertEquals(0L, s.totalOutputTokens)
        assertEquals(0L, s.totalCacheTokens)
        assertNull(s.promptHistogram)
        assertNull(s.genHistogram)
    }

    /**
     * Successful streamed row in the v265 shape (store.ActivityLogEntry). id/model/cache/draft
     * values are those llama-swap reported for request 179 on this host before its restart (no
     * successful row was available to capture afterwards); the other values are illustrative.
     */
    @Test
    fun `successful row with speeds, draft counts, metadata and unknown fields`() {
        val json =
            """
            {"data":[{"id":179,"timestamp":"2026-10-24T15:51:56.123456789-05:00","src":"ip:127.0.0.1",
            "model":"model-i","req_path":"/v1/chat/completions",
            "resp_content_type":"text/event-stream","resp_status_code":200,
            "tokens":{"cache_tokens":67685,"draft_tokens":85,"draft_acc_tokens":45,"input_tokens":312,
            "output_tokens":140,"prompt_per_second":1234.5,"tokens_per_second":41.27,"future":1},
            "duration_ms":4210,"has_capture":true,"metadata":{"session_id":"abc"},"new_field":[1,2]}],
            "page":1,"limit":100,"total":179,"total_pages":2}
            """.trimIndent()

        val row = page(json).data.single()

        assertEquals(LlamaSwapStatusKind.OK, LlamaSwapStatusKind.of(row.respStatusCode))
        assertEquals(Instant.parse("2026-10-24T20:51:56.123Z").toEpochMilli(), row.epochMs)
        assertEquals(67_685L, row.tokens.cacheTokensOrNull)
        assertEquals(1234.5, row.tokens.promptSpeedOrNull!!, 0.0)
        assertEquals(41.27, row.tokens.genSpeedOrNull!!, 0.0)
        assertEquals(45.0 / 85.0, row.tokens.draftAcceptance!!, 1e-9)
        assertFalse(row.tokens.isEmpty)
        assertTrue(row.hasCapture)
        assertEquals(mapOf("session_id" to "abc"), row.metadata)
        assertEquals("", row.errorMsg)
    }

    @Test
    fun `minus one sentinels mean not reported`() {
        val json =
            """{"data":[{"id":2,"resp_status_code":200,"tokens":{"cache_tokens":-1,"draft_tokens":-1,""" +
                """"draft_acc_tokens":-1,"input_tokens":10,"output_tokens":5,"prompt_per_second":-1,""" +
                """"tokens_per_second":-1}}]}"""

        val t = page(json).data.single().tokens

        assertNull(t.promptSpeedOrNull)
        assertNull(t.genSpeedOrNull)
        assertNull(t.cacheTokensOrNull)
        assertNull(t.draftAcceptance)
        assertFalse(t.isEmpty)
    }

    @Test
    fun `missing and null fields fall back to defaults`() {
        val row = page("""{"data":[{"id":3,"tokens":null,"timestamp":null,"model":null}]}""").data.single()

        assertEquals(3L, row.id)
        assertEquals("", row.model)
        assertNull(row.epochMs)
        assertEquals(LlamaSwapStatusKind.UNKNOWN, LlamaSwapStatusKind.of(row.respStatusCode))
        assertTrue(row.tokens.isEmpty)
        assertTrue(page("{}").data.isEmpty())
    }

    @Test
    fun `stats histograms parse with camelCase binSize`() {
        val s =
            stats(
                """{"total_requests":179,"total_input_tokens":50000,"total_output_tokens":9000,""" +
                    """"total_cache_tokens":2000000,"prompt_histogram":{"bins":[1,0,3],"min":100,"max":900,""" +
                    """"binSize":266.6,"p50":420.5,"p95":850,"p99":890},"gen_histogram":{"bins":[2],"min":40,""" +
                    """"max":40,"binSize":0,"p50":40,"p95":40,"p99":40}}""",
            )

        assertEquals(179L, s.totalRequests)
        assertEquals(2_000_000L, s.totalCacheTokens)
        assertEquals(listOf(1, 0, 3), s.promptHistogram!!.bins)
        assertEquals(266.6, s.promptHistogram.binSize, 0.0)
        assertEquals(420.5, s.promptHistogram.p50, 0.0)
        assertEquals(40.0, s.genHistogram!!.p99, 0.0)
    }

    @Test
    fun `status kinds`() {
        assertEquals(LlamaSwapStatusKind.OK, LlamaSwapStatusKind.of(200))
        assertEquals(LlamaSwapStatusKind.OK, LlamaSwapStatusKind.of(204))
        assertEquals(LlamaSwapStatusKind.CLIENT_DISCONNECTED, LlamaSwapStatusKind.of(499))
        assertEquals(LlamaSwapStatusKind.ERROR, LlamaSwapStatusKind.of(400))
        assertEquals(LlamaSwapStatusKind.ERROR, LlamaSwapStatusKind.of(502))
        assertEquals(LlamaSwapStatusKind.ERROR, LlamaSwapStatusKind.of(304))
        assertEquals(LlamaSwapStatusKind.UNKNOWN, LlamaSwapStatusKind.of(0))
    }

    @Test
    fun `rfc3339 parsing handles offsets, Z, nanos and garbage`() {
        assertEquals(0L, parseRfc3339Millis("1970-01-01T00:00:00Z"))
        assertEquals(3_600_000L, parseRfc3339Millis("1970-01-01T00:00:00-01:00"))
        assertEquals(1L, parseRfc3339Millis("1970-01-01T00:00:00.001999999Z"))
        assertNull(parseRfc3339Millis(""))
        assertNull(parseRfc3339Millis("yesterday"))
    }

    @Test
    fun `merge dedupes by id, sorts newest first and caps`() {
        fun e(id: Long) = LlamaSwapActivityEntry(id = id, model = "m$id")
        val merged = mergeActivity(listOf(e(3), e(2), e(1)), listOf(e(5), e(4), e(3)), limit = 4)
        assertEquals(listOf(5L, 4L, 3L, 2L), merged.map { it.id })
    }

    @Test
    fun `activity event carries the new row id`() {
        assertEquals(
            LlamaSwapEvent.Activity(180),
            LlamaSwapEventParser.parsePayload("""{"type":"activity","data":"{\"id\":180}"}"""),
        )
        // Undecodable id still signals "something new".
        assertEquals(
            LlamaSwapEvent.Activity(0),
            LlamaSwapEventParser.parsePayload("""{"type":"activity","data":"oops"}"""),
        )
    }
}
