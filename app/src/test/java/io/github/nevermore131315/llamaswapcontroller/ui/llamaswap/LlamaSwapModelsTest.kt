package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.InMemoryLlamaSwapSettings
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapApi
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapEvent
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapModel
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapModelListEntry
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapRunningEntry
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapSettings
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapState
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapVersion
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.mergeModelsAndRunning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

private fun model(
    id: String,
    state: String = LlamaSwapState.STOPPED,
    name: String = id.uppercase(),
    peer: String = "",
) = LlamaSwapModel(id = id, name = name, state = state, peerId = peer)

class EvictionPolicyTest {
    private val models =
        listOf(
            model("a", LlamaSwapState.READY),
            model("b", LlamaSwapState.STOPPED),
            model("c", LlamaSwapState.STARTING),
            model("d", LlamaSwapState.STOPPING),
            model("peer", LlamaSwapState.READY, peer = "other-box"),
        )

    @Test
    fun `loading evicts every other active local model`() {
        val evicted = EvictionPolicy.evictedBy(LlamaSwapAction.Load("b"), models)
        assertEquals(listOf("a", "c"), evicted.map { it.id })
    }

    @Test
    fun `loading the only running model evicts nothing`() {
        val evicted = EvictionPolicy.evictedBy(LlamaSwapAction.Load("a"), listOf(model("a", "ready"), model("b")))
        assertTrue(evicted.isEmpty())
    }

    @Test
    fun `loading when nothing runs evicts nothing`() {
        assertTrue(EvictionPolicy.evictedBy(LlamaSwapAction.Load("b"), listOf(model("a"), model("b"))).isEmpty())
    }

    @Test
    fun `unload stops only the target and only if active`() {
        assertEquals(listOf("a"), EvictionPolicy.evictedBy(LlamaSwapAction.Unload("a"), models).map { it.id })
        assertTrue(EvictionPolicy.evictedBy(LlamaSwapAction.Unload("b"), models).isEmpty())
    }

    @Test
    fun `unload all lists every active local model`() {
        assertEquals(listOf("a", "c"), EvictionPolicy.evictedBy(LlamaSwapAction.UnloadAll, models).map { it.id })
    }

    @Test
    fun `unknown non-empty states count as active`() {
        assertTrue(LlamaSwapState.isActive("running"))
        assertTrue(!LlamaSwapState.isActive("stopped"))
        assertTrue(!LlamaSwapState.isActive("shutdown"))
    }
}

class LlamaSwapNameFallbackTest {
    @Test
    fun `display name falls back to id when name is blank`() {
        assertEquals(
            "Qwen 7B Q4",
            LlamaSwapModel(id = "qwen-7b-q4", name = "Qwen 7B Q4").displayName,
        )
        assertEquals("llama-8b-q5", LlamaSwapModel(id = "llama-8b-q5", name = "").displayName)
        assertEquals("x", LlamaSwapModel(id = "x", name = "   ").displayName)
    }

    @Test
    fun `merge marks running models and keeps unlisted running ones`() {
        val merged =
            mergeModelsAndRunning(
                listOf(LlamaSwapModelListEntry("a", "Alpha"), LlamaSwapModelListEntry("b", "")),
                listOf(LlamaSwapRunningEntry("b", "starting"), LlamaSwapRunningEntry("hidden", "ready", "Hidden")),
            )
        assertEquals(listOf("a", "b", "hidden"), merged.map { it.id })
        assertEquals(listOf("stopped", "starting", "ready"), merged.map { it.state })
        assertEquals("b", merged[1].displayName)
        assertTrue(merged[2].unlisted)
    }

    @Test
    fun `ui state display name falls back to id for unknown models`() {
        val state = LlamaSwapModelsUiState(models = listOf(model("a", name = "")))
        assertEquals("a", state.displayName("a"))
        assertEquals("zzz", state.displayName("zzz"))
    }

    @Test
    fun `elapsed timer formatting`() {
        assertEquals("0:00", formatElapsed(0))
        assertEquals("0:23", formatElapsed(23_400))
        assertEquals("12:05", formatElapsed(725_000))
        assertEquals("1:02:03", formatElapsed(3_723_000))
    }

    @Test
    fun `backoff doubles and caps at 30s`() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..7).map { LlamaSwapModelsViewModel.backoffMs(it) },
        )
    }
}

/** Fake server: never touches the network. Events are pushed through [events]. */
private class FakeLlamaSwapApi : LlamaSwapApi {
    var events = Channel<LlamaSwapEvent>(Channel.UNLIMITED)
    var eventsCalls = 0
    var snapshot: List<LlamaSwapModel> = emptyList()
    var snapshotError: Exception? = null
    val calls = mutableListOf<String>()
    var loadError: Exception? = null

    override suspend fun version() = LlamaSwapVersion("v265", "d1e743f", "2026-10-14T08:30:34Z")

    override suspend fun models(): List<LlamaSwapModel> {
        snapshotError?.let { throw it }
        return snapshot
    }

    override suspend fun load(modelId: String) {
        calls += "load:$modelId"
        loadError?.let { throw it }
    }

    override suspend fun unload(modelId: String) {
        calls += "unload:$modelId"
    }

    override suspend fun unloadAll() {
        calls += "unloadAll"
    }

    override fun events(): Flow<LlamaSwapEvent> {
        eventsCalls++
        return events.receiveAsFlow()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LlamaSwapModelsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = FakeLlamaSwapApi()
    private var nowMs = 0L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newVm() =
        LlamaSwapModelsViewModel(
            settings = InMemoryLlamaSwapSettings("http://localhost:8080"),
            api = api,
            actionScope = backgroundScope,
            clock = { nowMs },
        )

    private suspend fun push(vararg models: LlamaSwapModel) {
        api.events.send(LlamaSwapEvent.ModelStatus(models.toList()))
    }

    @Test
    fun `no stream until a server url is set`() =
        runTest(dispatcher) {
            val settings = InMemoryLlamaSwapSettings(LlamaSwapSettings.DEFAULT_BASE_URL)
            val vm =
                LlamaSwapModelsViewModel(
                    settings = settings,
                    api = api,
                    actionScope = backgroundScope,
                    clock = { nowMs },
                )
            vm.start()
            runCurrent()
            assertEquals(0, api.eventsCalls)
            assertEquals(LlamaSwapConnection.Idle, vm.uiState.value.connection)

            settings.setBaseUrl("http://localhost:8080")
            runCurrent()
            assertEquals(1, api.eventsCalls)
            assertEquals("http://localhost:8080", vm.uiState.value.baseUrl)
            vm.stop()
        }

    @Test
    fun `activity events on the stream are forwarded to activityEvents`() =
        runTest(dispatcher) {
            val vm = newVm()
            val seen = mutableListOf<Long>()
            backgroundScope.launch { vm.activityEvents.collect { seen += it } }
            vm.start()
            runCurrent()

            api.events.send(LlamaSwapEvent.Activity(180))
            api.events.send(LlamaSwapEvent.Other("inflight"))
            runCurrent()

            assertEquals(listOf(180L), seen)
            assertEquals(LlamaSwapConnection.Live, vm.uiState.value.connection)
            vm.stop()
        }

    @Test
    fun `event stream drives models and live status`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.start()
            push(model("a", "ready"), model("b"))
            runCurrent()

            val s = vm.uiState.value
            assertEquals(LlamaSwapConnection.Live, s.connection)
            assertTrue(s.hasSnapshot)
            assertEquals(listOf("a", "b"), s.models.map { it.id })
            vm.stop()
            assertEquals(LlamaSwapConnection.Idle, vm.uiState.value.connection)
        }

    @Test
    fun `load that evicts nothing fires immediately`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.start()
            push(model("a"), model("b"))
            runCurrent()

            vm.request(LlamaSwapAction.Load("b"))
            runCurrent()

            assertNull(vm.uiState.value.confirm)
            assertEquals(listOf("load:b"), api.calls)
            vm.stop()
        }

    @Test
    fun `load that evicts a running model asks first and names it`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.start()
            push(model("a", "ready", name = "Alpha"), model("b"))
            runCurrent()

            vm.request(LlamaSwapAction.Load("b"))
            runCurrent()

            val confirm = vm.uiState.value.confirm!!
            assertEquals(LlamaSwapAction.Load("b"), confirm.action)
            assertEquals(listOf("Alpha"), confirm.evicted.map { it.displayName })
            assertTrue(api.calls.isEmpty())

            vm.confirm()
            runCurrent()
            assertNull(vm.uiState.value.confirm)
            assertEquals(listOf("load:b"), api.calls)
            vm.stop()
        }

    @Test
    fun `dismissing the confirm does nothing`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.start()
            push(model("a", "starting"), model("b"))
            runCurrent()

            vm.request(LlamaSwapAction.UnloadAll)
            vm.dismissConfirm()
            runCurrent()

            assertNull(vm.uiState.value.confirm)
            assertTrue(api.calls.isEmpty())
            vm.stop()
        }

    @Test
    fun `confirm re-prompts if another model started while the dialog was open`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.start()
            push(model("a", "ready"), model("b"), model("c"))
            runCurrent()
            vm.request(LlamaSwapAction.Load("b"))
            push(model("a", "stopped"), model("b"), model("c", "starting"))
            runCurrent()

            vm.confirm()
            runCurrent()

            assertEquals(
                listOf("c"),
                vm.uiState.value.confirm!!
                    .evicted
                    .map { it.id },
            )
            assertTrue(api.calls.isEmpty())
            vm.stop()
        }

    @Test
    fun `unloading a running model asks first, unloading a stopped one does not`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.start()
            push(model("a", "ready"), model("b"))
            runCurrent()

            vm.request(LlamaSwapAction.Unload("b"))
            runCurrent()
            assertEquals(listOf("unload:b"), api.calls)

            vm.request(LlamaSwapAction.Unload("a"))
            assertEquals(
                LlamaSwapAction.Unload("a"),
                vm.uiState.value.confirm!!
                    .action,
            )
            vm.stop()
        }

    @Test
    fun `starting models get an elapsed timer origin that clears when ready`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.start()
            nowMs = 5_000
            push(model("a", "starting"))
            runCurrent()
            assertEquals(5_000L, vm.uiState.value.loadingSinceMs["a"])

            nowMs = 9_000
            push(model("a", "starting"))
            runCurrent()
            assertEquals("origin is kept, not reset per event", 5_000L, vm.uiState.value.loadingSinceMs["a"])

            push(model("a", "ready"))
            runCurrent()
            assertTrue(
                vm.uiState.value.loadingSinceMs
                    .isEmpty(),
            )
            vm.stop()
        }

    @Test
    fun `failed load surfaces an action error`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.start()
            push(model("a"))
            runCurrent()
            api.loadError = IOException("HTTP 502")

            vm.request(LlamaSwapAction.Load("a"))
            runCurrent()

            val err = vm.uiState.value.actionError!!
            assertEquals(LlamaSwapAction.Load("a"), err.action)
            assertEquals("HTTP 502", err.message)
            assertTrue(
                vm.uiState.value.pendingLoads
                    .isEmpty(),
            )
            vm.stop()
        }

    @Test
    fun `stream end falls back to a REST snapshot and reconnects with backoff`() =
        runTest(dispatcher) {
            api.snapshot = listOf(model("a", "ready"))
            val vm = newVm()
            vm.start()
            runCurrent()
            assertEquals(1, api.eventsCalls)

            api.events.close(IOException("connection reset"))
            api.events = Channel(Channel.UNLIMITED)
            runCurrent()

            val s = vm.uiState.value
            val retrying = s.connection as LlamaSwapConnection.Retrying
            assertEquals(1, retrying.attempt)
            assertEquals("connection reset", retrying.reason)
            assertEquals(listOf("a"), s.models.map { it.id })

            advanceUntilIdleBounded()
            assertEquals(2, api.eventsCalls)
            vm.stop()
        }

    @Test
    fun `no snapshot and REST failure exposes a load error`() =
        runTest(dispatcher) {
            api.snapshotError = IOException("timeout")
            api.events.close(IOException("refused"))
            val vm = newVm()
            vm.start()
            runCurrent()

            assertEquals("timeout", vm.uiState.value.loadError)
            vm.stop()
        }

    private fun TestScope.advanceUntilIdleBounded() {
        testScheduler.advanceTimeBy(1_001)
        runCurrent()
    }
}
