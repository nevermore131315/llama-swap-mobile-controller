package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.InMemoryLlamaSwapSettings
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapApi
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapChatApi
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapChatDelta
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapChatMessage
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapEvent
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapModel
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapState
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapStreamException
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

private fun m(
    id: String,
    state: String = LlamaSwapState.STOPPED,
    name: String = id.uppercase(),
) = LlamaSwapModel(id = id, name = name, state = state)

/** Fake chat server: deltas are pushed through [deltas]; never touches the network. */
private class FakeChatApi : LlamaSwapChatApi {
    var deltas = Channel<LlamaSwapChatDelta>(Channel.UNLIMITED)
    val requests = mutableListOf<Pair<String, List<LlamaSwapChatMessage>>>()
    var cancelled = 0

    override fun streamChat(
        modelId: String,
        messages: List<LlamaSwapChatMessage>,
    ): Flow<LlamaSwapChatDelta> {
        requests += modelId to messages
        val ch = deltas
        return flow { for (d in ch) emit(d) }
            .onCompletion { cause -> if (cause is kotlinx.coroutines.CancellationException) cancelled++ }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LlamaSwapPlaygroundViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = FakeChatApi()
    private val lock = PlaygroundStreamLock()
    private val models = MutableStateFlow(listOf(m("a", LlamaSwapState.READY, "Alpha"), m("b", name = "Bravo")))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newVm(): LlamaSwapPlaygroundViewModel {
        val vm =
            LlamaSwapPlaygroundViewModel(
                settings = InMemoryLlamaSwapSettings("http://localhost:8080"),
                models = models,
                api = api,
                lock = lock,
                clock = { 1_000L },
            )
        runCurrent()
        return vm
    }

    private fun LlamaSwapPlaygroundViewModel.say(text: String) {
        setInput(text)
        send()
    }

    @Test
    fun `defaults to the ready model and follows it until the user picks`() =
        runTest(dispatcher) {
            val vm = newVm()
            assertEquals("a", vm.uiState.value.selectedModelId)

            models.value = listOf(m("a"), m("b", LlamaSwapState.STARTING))
            runCurrent()
            assertEquals("b", vm.uiState.value.selectedModelId)

            vm.pickModel("a")
            models.value = listOf(m("a"), m("b", LlamaSwapState.READY))
            runCurrent()
            assertEquals("a", vm.uiState.value.selectedModelId)
        }

    @Test
    fun `nothing running defaults to the first model`() =
        runTest(dispatcher) {
            models.value = listOf(m("x"), m("y"))
            val vm = newVm()
            assertEquals("x", vm.uiState.value.selectedModelId)
        }

    @Test
    fun `sending to the running model streams without asking and holds the lock`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.say("  hi  ")
            runCurrent()

            assertNull(vm.uiState.value.confirm)
            assertEquals(listOf("a" to listOf(LlamaSwapChatMessage("user", "hi"))), api.requests)
            assertTrue(lock.streaming.value)
            assertEquals("", vm.uiState.value.input)
            val reply =
                vm.uiState.value.messages
                    .last()
            assertTrue(reply.awaitingFirstToken)

            api.deltas.send(LlamaSwapChatDelta.Reasoning("thinking"))
            api.deltas.send(LlamaSwapChatDelta.Content("Hel"))
            api.deltas.send(LlamaSwapChatDelta.Content("lo"))
            runCurrent()
            val streaming =
                vm.uiState.value.messages
                    .last()
            assertEquals("Hello", streaming.content)
            assertEquals("thinking", streaming.reasoning)
            assertFalse(streaming.awaitingFirstToken)
            assertTrue(vm.uiState.value.isStreaming)

            api.deltas.close()
            runCurrent()
            assertEquals(
                PlaygroundStatus.DONE,
                vm.uiState.value.messages
                    .last()
                    .status,
            )
            assertFalse(lock.streaming.value)
            assertFalse(vm.uiState.value.isStreaming)
        }

    @Test
    fun `sending to a stopped model while another runs asks first and names it`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.pickModel("b")
            vm.say("hi")
            runCurrent()

            val confirm = vm.uiState.value.confirm!!
            assertEquals("b", confirm.modelId)
            assertEquals(listOf("Alpha"), confirm.evicted.map { it.displayName })
            assertTrue(api.requests.isEmpty())
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
            assertFalse(lock.streaming.value)

            vm.confirmSend()
            runCurrent()
            assertNull(vm.uiState.value.confirm)
            assertEquals("b", api.requests.single().first)
            assertTrue(lock.streaming.value)
            vm.stop()
            runCurrent()
        }

    @Test
    fun `dismissing the confirm sends nothing and keeps the input`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.pickModel("b")
            vm.say("hi")
            vm.dismissConfirm()
            runCurrent()

            assertNull(vm.uiState.value.confirm)
            assertTrue(api.requests.isEmpty())
            assertEquals("hi", vm.uiState.value.input)
        }

    @Test
    fun `confirm re-prompts if another model started while the dialog was open`() =
        runTest(dispatcher) {
            models.value = listOf(m("a", LlamaSwapState.READY), m("b"), m("c"))
            val vm = newVm()
            vm.pickModel("b")
            vm.say("hi")
            models.value = listOf(m("a"), m("b"), m("c", LlamaSwapState.STARTING))
            runCurrent()

            vm.confirmSend()
            runCurrent()

            assertEquals(
                listOf("c"),
                vm.uiState.value.confirm!!
                    .evicted
                    .map { it.id },
            )
            assertTrue(api.requests.isEmpty())
        }

    @Test
    fun `nothing running means no confirm`() =
        runTest(dispatcher) {
            models.value = listOf(m("a"), m("b"))
            val vm = newVm()
            vm.pickModel("b")
            vm.say("hi")
            runCurrent()

            assertNull(vm.uiState.value.confirm)
            assertEquals("b", api.requests.single().first)
            vm.stop()
            runCurrent()
        }

    @Test
    fun `stop cancels the request, keeps partial text and releases the lock`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.say("hi")
            api.deltas.send(LlamaSwapChatDelta.Content("partial"))
            runCurrent()

            vm.stop()
            runCurrent()

            val reply =
                vm.uiState.value.messages
                    .last()
            assertEquals(PlaygroundStatus.STOPPED, reply.status)
            assertEquals("partial", reply.content)
            assertEquals(1, api.cancelled)
            assertFalse(lock.streaming.value)
            assertFalse(vm.uiState.value.isStreaming)
        }

    @Test
    fun `stream error mid-reply shows inline and keeps partial text`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.say("hi")
            api.deltas.send(LlamaSwapChatDelta.Content("partial"))
            api.deltas.close(LlamaSwapStreamException("upstream process exited"))
            runCurrent()

            val reply =
                vm.uiState.value.messages
                    .last()
            assertEquals(PlaygroundStatus.ERROR, reply.status)
            assertEquals("upstream process exited", reply.error)
            assertEquals("partial", reply.content)
            assertFalse(lock.streaming.value)
        }

    @Test
    fun `follow-up sends prior turns and skips replies with no text`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.say("one")
            api.deltas.send(LlamaSwapChatDelta.Reasoning("hmm"))
            api.deltas.send(LlamaSwapChatDelta.Content("1"))
            api.deltas.close()
            runCurrent()

            api.deltas = Channel(Channel.UNLIMITED)
            vm.say("two")
            api.deltas.close(LlamaSwapStreamException("boom"))
            runCurrent()

            api.deltas = Channel(Channel.UNLIMITED)
            vm.say("three")
            runCurrent()

            assertEquals(
                listOf(
                    LlamaSwapChatMessage("user", "one"),
                    LlamaSwapChatMessage("assistant", "1"),
                    LlamaSwapChatMessage("user", "three"),
                ),
                api.requests.last().second,
            )
            vm.stop()
            runCurrent()
        }

    @Test
    fun `cannot send or switch model while streaming`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.say("hi")
            runCurrent()
            vm.pickModel("b")
            vm.say("again")
            runCurrent()

            assertEquals("a", vm.uiState.value.selectedModelId)
            assertEquals(1, api.requests.size)
            vm.stop()
            runCurrent()
        }

    @Test
    fun `new chat stops the stream and clears history`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.say("hi")
            api.deltas.send(LlamaSwapChatDelta.Content("x"))
            runCurrent()

            vm.newChat()
            runCurrent()

            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
            assertEquals(1, api.cancelled)
            assertFalse(lock.streaming.value)
        }

    @Test
    fun `blank input does nothing`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.say("   ")
            runCurrent()
            assertTrue(api.requests.isEmpty())
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
        }
}

/** Models-tab side of the Playground lock. */
@OptIn(ExperimentalCoroutinesApi::class)
class LlamaSwapModelsPlaygroundLockTest {
    private val dispatcher = StandardTestDispatcher()
    private val lock = PlaygroundStreamLock()
    private val calls = mutableListOf<String>()

    private val api =
        object : LlamaSwapApi {
            override suspend fun version() = LlamaSwapVersion()

            override suspend fun models(): List<LlamaSwapModel> = listOf(m("a", LlamaSwapState.READY), m("b"))

            override suspend fun load(modelId: String) {
                calls += "load:$modelId"
            }

            override suspend fun unload(modelId: String) {
                calls += "unload:$modelId"
            }

            override suspend fun unloadAll() {
                calls += "unloadAll"
            }

            override fun events(): Flow<LlamaSwapEvent> = emptyFlow()
        }

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
            clock = { 0L },
            streamLock = lock,
        ).also {
            it.refresh()
            runCurrent()
        }

    @Test
    fun `actions are ignored while the playground streams`() =
        runTest(dispatcher) {
            val vm = newVm()
            lock.acquire()
            runCurrent()
            assertTrue(vm.uiState.value.playgroundStreaming)

            vm.request(LlamaSwapAction.Load("b"))
            vm.request(LlamaSwapAction.Unload("a"))
            vm.request(LlamaSwapAction.UnloadAll)
            runCurrent()
            assertNull(vm.uiState.value.confirm)
            assertTrue(calls.isEmpty())

            lock.release()
            runCurrent()
            assertFalse(vm.uiState.value.playgroundStreaming)
            vm.request(LlamaSwapAction.Unload("b"))
            runCurrent()
            assertEquals(listOf("unload:b"), calls)
        }

    @Test
    fun `an open confirm is dropped when the playground starts streaming`() =
        runTest(dispatcher) {
            val vm = newVm()
            vm.request(LlamaSwapAction.Load("b"))
            runCurrent()
            assertTrue(vm.uiState.value.confirm != null)

            lock.acquire()
            runCurrent()
            assertNull(vm.uiState.value.confirm)
            vm.confirm()
            runCurrent()
            assertTrue(calls.isEmpty())
            lock.release()
        }

    @Test
    fun `lock counts overlapping holders`() {
        val l = PlaygroundStreamLock()
        l.acquire()
        l.acquire()
        l.release()
        assertTrue(l.streaming.value)
        l.release()
        assertFalse(l.streaming.value)
        l.release()
        assertFalse(l.streaming.value)
    }
}
