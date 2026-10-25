package io.github.nevermore131315.llamaswapcontroller.data.remote

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object OkHttpProvider {
    // One pool shared by every llama-swap client (REST, SSE, chat streaming).
    private val connectionPool = ConnectionPool(5, 30, TimeUnit.SECONDS)

    /** Plain client: no cookies or injected headers. */
    val external: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectionPool(connectionPool)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
