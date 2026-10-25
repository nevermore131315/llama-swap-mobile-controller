package io.github.nevermore131315.llamaswapcontroller.data.llamaswap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LlamaSwapPerformanceParsingTest {
    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("llamaswap/$name")).readText()

    private fun parse(json: String) = LlamaSwapJson.decodeFromString(LlamaSwapPerformance.serializer(), json)

    private val hour = 60 * 60 * 1000L

    /** Real `GET /api/performance?after=…` from llama-swap v265: 2 ticks, 3 GPUs interleaved by id. */
    private val real by lazy { parse(resource("performance-v265-snapshot.json")) }

    @Test
    fun `real capture parses every field`() {
        assertTrue(real.enabled)
        assertEquals(6, real.gpuStats.size)
        assertEquals(2, real.sysStats.size)

        val g = real.gpuStats.first()
        assertEquals("2026-10-24T18:42:20.684199407-05:00", g.timestamp)
        assertEquals(0, g.id)
        assertEquals("NVIDIA GeForce RTX 3090", g.name)
        assertEquals("GPU-00000000-0000-0000-0000-000000000000", g.uuid)
        assertEquals(44.0, g.tempC, 0.0)
        assertEquals(34.0, g.vramTempC, 0.0)
        assertEquals(0.0, g.gpuUtilPct, 0.0)
        assertEquals(81.897, g.memUtilPct, 0.001)
        assertEquals(20127.0, g.memUsedMb, 0.0)
        assertEquals(24576.0, g.memTotalMb, 0.0)
        assertEquals(44.706, g.fanSpeedPct, 0.001)
        assertEquals(31.173, g.powerDrawW, 0.0)

        val s = real.sysStats.last()
        assertEquals(20, s.cpuUtilPerCore.size)
        assertEquals(64142.0, s.memTotalMb, 0.0)
        assertEquals(23627.0, s.memUsedMb, 0.0)
        assertEquals(446.0, s.memFreeMb, 0.0)
        assertEquals(32767.0, s.swapTotalMb, 0.0)
        assertEquals(9579.0, s.swapUsedMb, 0.0)
        assertEquals(17.86, s.loadAvg1, 0.0)
        assertEquals(16.94, s.loadAvg5, 0.0)
        assertEquals(13.03, s.loadAvg15, 0.0)
    }

    /** Body of the 503 llama-swap sends when monitoring is off (handleAPIPerformance). */
    @Test
    fun `disabled body parses as not enabled with no samples`() {
        val p = parse("""{"enabled":false}""")
        assertFalse(p.enabled)
        assertTrue(p.gpuStats.isEmpty())
        assertTrue(p.sysStats.isEmpty())
    }

    @Test
    fun `missing nulls and fractional ints degrade instead of failing`() {
        val p =
            parse(
                """{"enabled":true,"gpu_stats":[{"timestamp":"2026-10-24T18:00:00Z","id":3,"temp_c":41.5,""" +
                    """"power_draw_w":null,"extra":{"x":1}}],"sys_stats":null}""",
            )
        val g = p.gpuStats.single()
        assertEquals(3, g.id)
        assertEquals("", g.name)
        assertEquals(41.5, g.tempC, 0.0)
        assertEquals(0.0, g.powerDrawW, 0.0)
        assertTrue(p.sysStats.isEmpty())
    }

    @Test
    fun `merge groups real capture by gpu id in order with oldest first`() {
        val h = mergePerformance(PerformanceHistory(), real, hour)

        assertEquals(listOf(0, 1, 2), h.gpus.map { it.id })
        assertEquals(
            listOf("NVIDIA GeForce RTX 3090", "NVIDIA GeForce RTX 3080", "NVIDIA GeForce RTX 4070 Ti SUPER"),
            h.gpus.map { it.name },
        )
        h.gpus.forEach { assertEquals(2, it.samples.size) }
        val gpu1 = h.gpus[1]
        assertEquals(listOf(29.0, 35.0), gpu1.samples.map { it.utilPct })
        assertEquals(46.0, gpu1.latest!!.tempC, 0.0)
        assertEquals(1622.0 / 10240 * 100, gpu1.latest!!.memPct, 1e-9)
        assertTrue(gpu1.hasPower)

        assertEquals(2, h.sys.size)
        val sys = h.sys.last()
        assertEquals(20, sys.cores)
        assertEquals(77.828, sys.cpuPct!!, 0.001)
        assertEquals(17.86, sys.load1, 0.0)

        // Newest sample overall is GPU 2's second reading, kept verbatim for ?after=.
        assertEquals("2026-10-24T18:42:25.708780744-05:00", h.newestTimestamp)
        assertEquals(Instant.parse("2026-10-24T23:42:25.708780744Z"), h.newestInstant)
    }

    @Test
    fun `incremental merge appends dedupes and trims to the window`() {
        val base = mergePerformance(PerformanceHistory(), real, hour)
        // Same response again (overlapping poll) adds nothing.
        val same = mergePerformance(base, real, hour)
        assertEquals(base.gpus.map { it.samples.size }, same.gpus.map { it.samples.size })
        assertEquals(2, same.sys.size)

        // A sample 59m59s later keeps everything; one 61 minutes later trims the old ticks.
        fun tick(ts: String) =
            LlamaSwapPerformance(
                enabled = true,
                gpuStats = listOf(LlamaSwapGpuStat(timestamp = ts, id = 0, gpuUtilPct = 99.0)),
                sysStats = listOf(LlamaSwapSysStat(timestamp = ts)),
            )
        val later = mergePerformance(base, tick("2026-10-24T19:42:20.000-05:00"), hour)
        assertEquals(3, later.gpus[0].samples.size)
        assertEquals("NVIDIA GeForce RTX 3090", later.gpus[0].name)
        assertEquals(99.0, later.gpus[0].latest!!.utilPct, 0.0)
        assertEquals("2026-10-24T19:42:20.000-05:00", later.newestTimestamp)

        val muchLater = mergePerformance(base, tick("2026-10-24T19:43:30-05:00"), hour)
        assertEquals(listOf(0), muchLater.gpus.map { it.id })
        assertEquals(1, muchLater.gpus[0].samples.size)
        assertEquals(1, muchLater.sys.size)
        assertNull(muchLater.sys[0].cpuPct)
    }

    @Test
    fun `rows with bad timestamps are skipped and power absent is detected`() {
        val p =
            LlamaSwapPerformance(
                enabled = true,
                gpuStats =
                    listOf(
                        LlamaSwapGpuStat(timestamp = "garbage", id = 0),
                        LlamaSwapGpuStat(timestamp = "2026-10-24T18:00:00Z", id = 0, name = "AMD"),
                    ),
            )
        val h = mergePerformance(PerformanceHistory(), p, hour)
        assertEquals(
            1,
            h.gpus
                .single()
                .samples.size,
        )
        assertFalse(h.gpus.single().hasPower)
        assertTrue(mergePerformance(PerformanceHistory(), LlamaSwapPerformance(enabled = true), hour).isEmpty)
    }
}
