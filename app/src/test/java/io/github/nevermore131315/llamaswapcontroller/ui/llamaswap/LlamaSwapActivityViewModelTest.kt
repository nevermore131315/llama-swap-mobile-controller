package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.InMemoryLlamaSwapSettings
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapActivityApi
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapActivityEntry
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapActivityPage
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapActivityStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** In-memory activity store mimicking llama-swap's GET semantics (newest first, min_id inclusive). */
private class FakeActivityApi : LlamaSwapActivityApi {
    var rows: List<LlamaSwapActivityEntry> = emptyList()
    var error: Exception? = null
    val calls = mutableListOf<String>()

    fun add(vararg ids: Long) {
        rows = rows + ids.map { LlamaSwapActivityEntry(id = it, model = "m", respStatusCode = 200) }
    }

    override suspend fun activity(
        limit: Int,
        minId: Long,
    ): LlamaSwapActivityPage {
        calls += "activity:min=$minId"
        error?.let { throw it }
        val matching = rows.filter { it.id >= minId }.sortedByDescending { it.id }
        return LlamaSwapActivityPage(data = matching.take(limit), total = matching.size.toLong())
    }

    override suspend fun activityStats(): LlamaSwapActivityStats {
        calls += "stats"
        error?.let { throw it }
        return LlamaSwapActivityStats(totalRequests = rows.maxOfOrNull { it.id } ?: 0)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LlamaSwapActivityViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = FakeActivityApi()
    private val events = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    private val connection = MutableStateFlow<LlamaSwapConnection>(LlamaSwapConnection.Live)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm() =
        LlamaSwapActivityViewModel(
            settings = InMemoryLlamaSwapSettings("http://localhost:8080"),
            api = api,
            activityEvents = events,
            connection = connection,
            pollIntervalMs = 15_000,
        )

    private val LlamaSwapActivityViewModel.ids get() = uiState.value.entries.map { it.id }

    @Test
    fun `start loads stats and newest rows first`() =
        runTest(dispatcher) {
            api.add(1, 2, 3)
            val vm = newVm()
            vm.start()
            runCurrent()

            val s = vm.uiState.value
            assertTrue(s.hasSnapshot)
            assertEquals(listOf(3L, 2L, 1L), vm.ids)
            assertEquals(3L, s.stats?.totalRequests)
            assertEquals(ActivityUpdateMode.LIVE, s.mode)
            assertEquals(listOf("stats", "activity:min=0"), api.calls)
            vm.stop()
            assertEquals(ActivityUpdateMode.PAUSED, vm.uiState.value.mode)
        }

    @Test
    fun `activity event fetches only rows newer than the newest shown and prepends them`() =
        runTest(dispatcher) {
            api.add(1, 2)
            val vm = newVm()
            vm.start()
            runCurrent()
            api.calls.clear()

            api.add(3, 4)
            events.emit(3)
            events.emit(4) // burst coalesces into one fetch
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(listOf(4L, 3L, 2L, 1L), vm.ids)
            assertEquals(listOf("stats", "activity:min=3"), api.calls.take(2))
            assertFalse("never a full reload", "activity:min=0" in api.calls)
            vm.stop()
        }

    @Test
    fun `server restart with reset ids triggers a full reload`() =
        runTest(dispatcher) {
            api.add(178, 179)
            val vm = newVm()
            vm.start()
            runCurrent()
            vm.toggleExpanded(179)

            api.rows = emptyList()
            api.add(1) // in-memory store restarted: ids begin again at 1
            api.calls.clear()
            events.emit(1)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(listOf(1L), vm.ids)
            assertEquals(listOf("stats", "activity:min=0"), api.calls)
            assertTrue(
                vm.uiState.value.expanded
                    .isEmpty(),
            )
            vm.stop()
        }

    @Test
    fun `polls while the event stream is down and catches up when it returns`() =
        runTest(dispatcher) {
            connection.value = LlamaSwapConnection.Connecting
            api.add(1)
            val vm = newVm()
            vm.start()
            runCurrent()
            assertEquals(ActivityUpdateMode.POLLING, vm.uiState.value.mode)

            api.add(2)
            advanceTimeBy(15_000 + 400)
            runCurrent()
            assertEquals(listOf(2L, 1L), vm.ids)

            api.add(3)
            connection.value = LlamaSwapConnection.Live
            advanceTimeBy(400)
            runCurrent()
            assertEquals(ActivityUpdateMode.LIVE, vm.uiState.value.mode)
            assertEquals(listOf(3L, 2L, 1L), vm.ids)

            // No polling while live.
            api.calls.clear()
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(api.calls.isEmpty())
            vm.stop()
        }

    @Test
    fun `first failure shows an error, later failures keep the rows`() =
        runTest(dispatcher) {
            api.error = IOException("timeout")
            val vm = newVm()
            vm.start()
            runCurrent()
            assertFalse(vm.uiState.value.hasSnapshot)
            assertEquals("timeout", vm.uiState.value.loadError)

            api.error = null
            api.add(1)
            vm.refresh()
            runCurrent()
            assertEquals(listOf(1L), vm.ids)
            assertNull(vm.uiState.value.loadError)
            assertFalse(vm.uiState.value.isRefreshing)

            api.error = IOException("refused")
            vm.refresh()
            runCurrent()
            assertEquals(listOf(1L), vm.ids)
            assertEquals("refused", vm.uiState.value.loadError)
            vm.stop()
        }

    @Test
    fun `nothing is fetched until started and empty servers give an empty snapshot`() =
        runTest(dispatcher) {
            val vm = newVm()
            runCurrent()
            assertTrue(api.calls.isEmpty())

            vm.start()
            runCurrent()
            assertTrue(vm.uiState.value.hasSnapshot)
            assertTrue(
                vm.uiState.value.entries
                    .isEmpty(),
            )
            vm.stop()
        }

    @Test
    fun `toggle expands and collapses a row`() =
        runTest(dispatcher) {
            api.add(1)
            val vm = newVm()
            vm.start()
            runCurrent()

            vm.toggleExpanded(1)
            assertEquals(setOf(1L), vm.uiState.value.expanded)
            vm.toggleExpanded(1)
            assertTrue(
                vm.uiState.value.expanded
                    .isEmpty(),
            )
            vm.stop()
        }

    @Test
    fun `rows are capped at MAX_ROWS`() =
        runTest(dispatcher) {
            api.add(*(1L..250L).toList().toLongArray())
            val vm = newVm()
            vm.start()
            runCurrent()

            assertEquals(LlamaSwapActivityViewModel.MAX_ROWS, vm.ids.size)
            assertEquals(250L, vm.ids.first())
            vm.stop()
        }
}
