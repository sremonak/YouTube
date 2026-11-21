// Adapted from NewPipe (GPL-3.0): org.schabi.newpipe.util.potoken.JavaScriptUtil
package com.vidora.app.potoken

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

private fun JsonArray.str(i: Int): String? = (getOrNull(i) as? JsonPrimitive)?.contentOrNull

/**
 * Parses the raw challenge data obtained from the Create endpoint and returns an object that can be
 * embedded in a JavaScript snippet.
 */
fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = Json.parseToJsonElement(rawChallengeData).jsonArray
    val challengeData = if (scrambled.size > 1 && (scrambled[1] as? JsonPrimitive)?.isString == true) {
        Json.parseToJsonElement(descramble(scrambled[1].jsonPrimitive.content)).jsonArray
    } else {
        scrambled[0].jsonArray
    }

    val safeScript = (challengeData.getOrNull(1) as? JsonArray)
        ?.firstOrNull { it is JsonPrimitive && it.isString }?.jsonPrimitive?.content
    val trustedUrl = (challengeData.getOrNull(2) as? JsonArray)
        ?.firstOrNull { it is JsonPrimitive && it.isString }?.jsonPrimitive?.content

    return buildJsonObject {
        put("messageId", challengeData.str(0))
        putJsonObject("interpreterJavascript") {
            put("privateDoNotAccessOrElseSafeScriptWrappedValue", safeScript)
            put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", trustedUrl)
        }
        put("interpreterHash", challengeData.str(3))
        put("program", challengeData.str(4))
        put("globalName", challengeData.str(5))
        put("clientExperimentsStateBlob", challengeData.str(7))
    }.toString()
}

/**
 * Parses the raw integrity token data obtained from the GenerateIT endpoint to a JavaScript
 * `Uint8Array` and the token lifetime in seconds.
 */
fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val data = Json.parseToJsonElement(rawIntegrityTokenData).jsonArray
    return base64ToU8(data[0].jsonPrimitive.content) to data[1].jsonPrimitive.long
}

/** Converts a string to a JavaScript `Uint8Array` literal. */
fun stringToU8(identifier: String): String = newUint8Array(identifier.toByteArray())

/** Converts "97,98,99"-style JS output to YouTube's url-safe base64 poToken representation. */
fun u8ToBase64(poToken: String): String {
    val bytes = poToken.split(",").map { it.trim().toInt().toByte() }.toByteArray()
    return Base64.getEncoder().encodeToString(bytes).replace("+", "-").replace("/", "_")
}

private fun descramble(scrambledChallenge: String): String =
    base64ToByteArray(scrambledChallenge).map { (it + 97).toByte() }.toByteArray().decodeToString()

private fun base64ToU8(base64: String): String = newUint8Array(base64ToByteArray(base64))

private fun newUint8Array(contents: ByteArray): String =
    "new Uint8Array([" + contents.joinToString(separator = ",") { it.toUByte().toString() } + "])"

private fun base64ToByteArray(base64: String): ByteArray {
    val mod = base64.replace('-', '+').replace('_', '/').replace('.', '=')
    return try {
        Base64.getDecoder().decode(mod)
    } catch (e: IllegalArgumentException) {
        throw PoTokenException("Cannot base64 decode")
    }
}
