package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.InMemoryLlamaSwapSettings
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapGpuStat
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapHttpException
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapPerformance
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapPerformanceApi
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapSysStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.SerializationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Mimics llama-swap's `?after=` semantics (strictly newer than the given timestamp). */
private class FakePerformanceApi : LlamaSwapPerformanceApi {
    var enabled = true
    var error: Exception? = null
    val ticks = mutableListOf<String>()
    val calls = mutableListOf<String?>()

    fun addTick(second: Int) {
        ticks += "2026-10-24T18:00:%02d.5-05:00".format(second)
    }

    override suspend fun performance(after: String?): LlamaSwapPerformance {
        calls += after
        error?.let { throw it }
        if (!enabled) return LlamaSwapPerformance(enabled = false)
        val newer = if (after == null) ticks else ticks.filter { it > after }
        return LlamaSwapPerformance(
            enabled = true,
            gpuStats =
                newer.flatMap { ts ->
                    listOf(
                        LlamaSwapGpuStat(timestamp = ts, id = 1, name = "B", gpuUtilPct = 10.0),
                        LlamaSwapGpuStat(timestamp = ts, id = 0, name = "A", gpuUtilPct = 20.0),
                    )
                },
            sysStats = newer.map { LlamaSwapSysStat(timestamp = it, cpuUtilPerCore = listOf(10.0, 30.0)) },
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LlamaSwapPerformanceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = FakePerformanceApi()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm() =
        LlamaSwapPerformanceViewModel(
            settings = InMemoryLlamaSwapSettings("http://localhost:8080"),
            api = api,
            pollIntervalMs = 15_000,
        )

    @Test
    fun `first fetch is full then polls send after newest timestamp`() =
        runTest(dispatcher) {
            api.addTick(0)
            api.addTick(5)
            val vm = newVm()
            vm.start()
            runCurrent()

            assertEquals(listOf<String?>(null), api.calls)
            val s = vm.uiState.value
            assertEquals(true, s.enabled)
            assertEquals(listOf(0, 1), s.history.gpus.map { it.id })
            assertEquals(2, s.history.sys.size)
            assertEquals(
                20.0,
                s.history.sys
                    .last()
                    .cpuPct!!,
                0.0,
            )

            api.addTick(10)
            advanceTimeBy(15_001)
            assertEquals("2026-10-24T18:00:05.5-05:00", api.calls.last())
            assertEquals(
                3,
                vm.uiState.value.history.gpus[0]
                    .samples.size,
            )
            assertEquals("2026-10-24T18:00:10.5-05:00", vm.uiState.value.history.newestTimestamp)

            vm.stop()
            advanceTimeBy(60_000)
            assertEquals(2, api.calls.size)
        }

    @Test
    fun `failed poll keeps data and reports error, next success clears it`() =
        runTest(dispatcher) {
            api.addTick(0)
            val vm = newVm()
            vm.start()
            runCurrent()
            api.error = IOException("boom")
            advanceTimeBy(15_001)
            assertEquals("boom", vm.uiState.value.loadError)
            assertEquals(1, vm.uiState.value.history.sys.size)

            api.error = null
            advanceTimeBy(15_001)
            assertNull(vm.uiState.value.loadError)
            vm.stop()
        }

    @Test
    fun `first fetch failure leaves no snapshot`() =
        runTest(dispatcher) {
            api.error = IOException("refused")
            val vm = newVm()
            vm.start()
            runCurrent()
            assertFalse(vm.uiState.value.hasSnapshot)
            assertEquals("refused", vm.uiState.value.loadError)
            vm.stop()
        }

    @Test
    fun `disabled monitoring is reported and clears history`() =
        runTest(dispatcher) {
            api.addTick(0)
            val vm = newVm()
            vm.start()
            runCurrent()
            assertFalse(vm.uiState.value.history.isEmpty)

            api.enabled = false
            advanceTimeBy(15_001)
            assertEquals(false, vm.uiState.value.enabled)
            assertTrue(vm.uiState.value.history.isEmpty)
            vm.stop()
        }

    @Test
    fun `refresh reloads full history`() =
        runTest(dispatcher) {
            api.addTick(0)
            val vm = newVm()
            vm.start()
            runCurrent()
            vm.refresh()
            runCurrent()
            assertEquals(listOf<String?>(null, null), api.calls)
            assertFalse(vm.uiState.value.isRefreshing)
            assertEquals(1, vm.uiState.value.history.sys.size)
            vm.stop()
        }

    @Test
    fun `404 on performance reports missing endpoint, not a raw http error`() =
        runTest(dispatcher) {
            api.error = LlamaSwapHttpException(404, "HTTP 404: 404 page not found")
            val vm = newVm()
            vm.start()
            runCurrent()
            assertEquals(PerformanceApiGap.MissingEndpoint, vm.uiState.value.unsupported)
            assertTrue(vm.uiState.value.hasSnapshot)
            assertNull(vm.uiState.value.loadError)
            vm.stop()
        }

    @Test
    fun `unparseable performance payload reports unsupported and recovers on the next good poll`() =
        runTest(dispatcher) {
            api.error = SerializationException("Expected '{' but had '['")
            val vm = newVm()
            vm.start()
            runCurrent()
            assertEquals(PerformanceApiGap.UnparseablePayload, vm.uiState.value.unsupported)

            api.error = null
            api.addTick(0)
            advanceTimeBy(15_001)
            assertNull(vm.uiState.value.unsupported)
            assertEquals(true, vm.uiState.value.enabled)
            assertFalse(vm.uiState.value.history.isEmpty)
            vm.stop()
        }

    @Test
    fun `503 from the client layer maps to monitoring disabled without touching unsupported`() =
        runTest(dispatcher) {
            api.error = LlamaSwapHttpException(503, "HTTP 503")
            val vm = newVm()
            vm.start()
            runCurrent()
            assertEquals(false, vm.uiState.value.enabled)
            assertNull(vm.uiState.value.unsupported)
            vm.stop()
        }

    @Test
    fun `plain network failures stay in loadError and never claim unsupported`() =
        runTest(dispatcher) {
            api.error = IOException("connection refused")
            val vm = newVm()
            vm.start()
            runCurrent()
            assertEquals("connection refused", vm.uiState.value.loadError)
            assertNull(vm.uiState.value.unsupported)
            assertFalse(vm.uiState.value.hasSnapshot)
            vm.stop()
        }
}
