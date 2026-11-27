package com.vidora.app.account

import kotlinx.coroutines.CancellationException

/** A tracking HTTP acknowledgement must never be presented as a confirmed history write. */
class HistoryDelivery(
    private val send: suspend () -> Unit,
    private val verify: suspend () -> Boolean,
) {
    data class Result(val confirmed: Boolean, val verificationError: String? = null)
    suspend fun deliver(onSent: suspend () -> Unit = {}): Result {
        send() // A delivery failure is not a pending/confirmed write.
        onSent()
        return try { Result(verify()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result(false, e.message ?: "YouTube history check unavailable") }
    }
}
