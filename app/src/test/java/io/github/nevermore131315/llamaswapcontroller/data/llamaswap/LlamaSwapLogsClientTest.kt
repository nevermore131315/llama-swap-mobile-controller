package io.github.nevermore131315.llamaswapcontroller.data.llamaswap

import org.junit.Assert.assertEquals
import org.junit.Test

class LlamaSwapLogsClientTest {
    private val client = LlamaSwapLogsHttpClient(baseUrl = { "http://192.168.1.10:8080" })

    @Test
    fun allSourceUsesCombinedStream() {
        assertEquals("http://192.168.1.10:8080/logs/stream", client.url(LlamaSwapLogSource.ALL).toString())
    }

    @Test
    fun namedSourcesAppendTheirPath() {
        assertEquals("http://192.168.1.10:8080/logs/stream/proxy", client.url(LlamaSwapLogSource.PROXY).toString())
        assertEquals(
            "http://192.168.1.10:8080/logs/stream/upstream",
            client.url(LlamaSwapLogSource.UPSTREAM).toString(),
        )
        assertEquals("http://192.168.1.10:8080/logs/stream/http", client.url(LlamaSwapLogSource.HTTP).toString())
    }
}
