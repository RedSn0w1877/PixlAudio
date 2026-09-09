package com.theveloper.pixelplay.data.youtube.potoken

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * Portado casi literal de la app oficial de NewPipe (`util/potoken/JavaScriptUtil.kt`):
 * parsea la respuesta de los endpoints de BotGuard y la convierte en el JavaScript que
 * ejecuta `po_token.html` dentro del WebView.
 */
fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = JsonParser.array().from(rawChallengeData)

    val challengeData = if (scrambled.size > 1 && scrambled.isString(1)) {
        val descrambled = descramble(scrambled.getString(1))
        JsonParser.array().from(descrambled)
    } else {
        scrambled.getArray(0)
    }

    val messageId = challengeData.getString(0)
    val interpreterHash = challengeData.getString(3)
    val program = challengeData.getString(4)
    val globalName = challengeData.getString(5)
    val clientExperimentsStateBlob = challengeData.getString(7)

    val privateDoNotAccessOrElseSafeScriptWrappedValue =
        challengeData.getArray(1, null)?.find { it is String }
    val privateDoNotAccessOrElseTrustedResourceUrlWrappedValue =
        challengeData.getArray(2, null)?.find { it is String }

    return JsonWriter.string(
        JsonObject.builder()
            .value("messageId", messageId)
            .`object`("interpreterJavascript")
            .value(
                "privateDoNotAccessOrElseSafeScriptWrappedValue",
                privateDoNotAccessOrElseSafeScriptWrappedValue
            )
            .value(
                "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue",
                privateDoNotAccessOrElseTrustedResourceUrlWrappedValue
            )
            .end()
            .value("interpreterHash", interpreterHash)
            .value("program", program)
            .value("globalName", globalName)
            .value("clientExperimentsStateBlob", clientExperimentsStateBlob)
            .done()
    )
}

/** Del endpoint GenerateIT: el token de integridad y cuántos segundos dura. */
fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = JsonParser.array().from(rawIntegrityTokenData)
    return base64ToU8(integrityTokenData.getString(0)) to integrityTokenData.getLong(1)
}

fun stringToU8(identifier: String): String = newUint8Array(identifier.toByteArray())

/**
 * `Uint8Array::toString()` en JavaScript da "97,98,99"; esto lo convierte al base64
 * concreto (url-safe) que usa YouTube para el poToken.
 */
fun u8ToBase64(poToken: String): String =
    poToken.split(",")
        .map { it.toUByte().toByte() }
        .toByteArray()
        .toByteString()
        .base64()
        .replace("+", "-")
        .replace("/", "_")

private fun descramble(scrambledChallenge: String): String =
    base64ToByteString(scrambledChallenge)
        .map { (it + 97).toByte() }
        .toByteArray()
        .decodeToString()

private fun base64ToU8(base64: String): String = newUint8Array(base64ToByteString(base64))

private fun newUint8Array(contents: ByteArray): String =
    "new Uint8Array([" + contents.joinToString(separator = ",") { it.toUByte().toString() } + "])"

private fun base64ToByteString(base64: String): ByteArray {
    val base64Mod = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')

    return (base64Mod.decodeBase64() ?: throw PoTokenException("Cannot base64 decode"))
        .toByteArray()
}
