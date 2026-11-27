package com.vidora.app.data

import android.content.Context
import com.vidora.app.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

data class HomeRegion(val code: String, val method: String)

/** Country-level discovery only: no GPS permission, no account, no IP address persisted. */
class RegionRepository(context: Context) {
    private val prefs = context.getSharedPreferences("home_region", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(HomeRegion(deviceCountry(), "Device fallback"))
    val state = _state.asStateFlow()
    private var retryAfter = 0L
    private val client by lazy { Graph.http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build() }

    suspend fun resolve(settings: AppSettings): HomeRegion = mutex.withLock {
        GuestSources.country(settings.contentCountry)?.let { return@withLock HomeRegion(it, "Manual").also { _state.value = it } }
        if (!settings.autoRegionByIp) return@withLock HomeRegion(deviceCountry(), "Device").also { _state.value = it }
        val cached = GuestSources.country(prefs.getString("country", null))
        val now = System.currentTimeMillis()
        val age = now - prefs.getLong("at", 0)
        if (cached != null && age in 0 until 6 * 3_600_000L) {
            return@withLock HomeRegion(cached, "IP country · cached").also { _state.value = it }
        }
        if (now < retryAfter) return@withLock _state.value
        try {
            val code = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url("https://api.country.is/").header("User-Agent", "Vidora/1.0").build()).execute().use { r ->
                    check(r.isSuccessful)
                    GuestSources.country(Json.parseToJsonElement(r.body.string()).jsonObject["country"]?.jsonPrimitive?.content)
                        ?: error("Country unavailable")
                }
            }
            prefs.edit().putString("country", code).putLong("at", now).apply()
            HomeRegion(code, "IP country").also { _state.value = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            retryAfter = now + 60_000
            HomeRegion(cached ?: deviceCountry(), if (cached != null) "IP country · saved" else "Device fallback").also { _state.value = it }
        }
    }

    private fun deviceCountry() = GuestSources.country(Locale.getDefault().country) ?: "US"
}
