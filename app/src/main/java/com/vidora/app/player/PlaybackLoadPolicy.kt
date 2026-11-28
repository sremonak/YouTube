package com.vidora.app.player

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.vidora.app.data.ProviderHealth

/** Let the app classify permanent/access errors; don't repeatedly hammer a failing stream. */
@androidx.media3.common.util.UnstableApi
class PlaybackLoadPolicy : DefaultLoadErrorHandlingPolicy(2) {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val http = generateSequence(info.exception as Throwable?) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        if (http?.responseCode in listOf(401, 403, 404, 410, 429, 451)) return C.TIME_UNSET
        if (info.errorCount > 2) return C.TIME_UNSET
        return super.getRetryDelayMsFor(info).let { if (it == C.TIME_UNSET) it else ProviderHealth.backoff(info.errorCount - 1) }
    }
}
