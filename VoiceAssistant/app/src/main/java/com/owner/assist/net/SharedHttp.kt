package com.owner.assist.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Process-wide OkHttp client. Single thread pool, single connection pool.
 *
 * Network clients (Deepgram STT/TTS, Groq, DeepSeek) all share this instance
 * to avoid thread-pool fragmentation under spam-toggle ON/OFF cycles.
 */
object SharedHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // streaming sockets must not time out
            .build()
    }
}
