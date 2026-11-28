package com.vidora.app

import com.vidora.app.data.awaitResponse
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class HttpCancellationTest {
    @Test fun cancellingAwaitClosesTheActualHttpCall() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val call = OkHttpClient().newCall(Request.Builder().url(server.url("/slow")).build())
        try {
            val pending = async { call.awaitResponse().use { it.code } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS)) }
            pending.cancelAndJoin()
            assertTrue(call.isCanceled())
        } finally { server.shutdown() }
    }
}
