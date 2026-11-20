package com.theveloper.pixelplay.data.brain.source

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The remote half of the brain's off switches (docs/brain-spec.md, 3.7 "Switches"). It follows
 * `data/youtube/StreamRemoteFlags.kt`: the app already fetches `remote/config.json` (the iOS repo's
 * file, one switch file for both apps) and saves the copy it got. This reads that saved copy and
 * never touches the network, so the refresh worker asks it for free and an offline phone is not
 * held up. The key is `brain.refresh.enabled`; a missing file, a missing key or text that is not
 * JSON all mean "on", because a switch that is off by accident would silently disable the feature.
 */
object BrainRemoteConfig {
    private val json = Json { ignoreUnknownKeys = true }

    /** False only when [configText] says `{"brain": {"refresh": {"enabled": false}}}`. */
    fun refreshAllowed(configText: String?): Boolean {
        if (configText.isNullOrBlank()) return true
        return runCatching {
            val root = json.parseToJsonElement(configText).jsonObject
            val enabled = ((root["brain"] as? JsonObject)?.get("refresh") as? JsonObject)?.get("enabled")?.jsonPrimitive?.booleanOrNull
            enabled != false
        }.getOrDefault(true)
    }

    /**
     * False only when [configText] says `{"brain": {"assist": {"enabled": false}}}`: the owner can switch the batch jobs of "Let Taizo
     * help the engine" off for every phone. A missing file, a missing key or text that is not JSON means "on" (the local switch is off by default).
     */
    fun assistAllowed(configText: String?): Boolean {
        if (configText.isNullOrBlank()) return true
        return runCatching {
            val root = json.parseToJsonElement(configText).jsonObject
            val enabled = ((root["brain"] as? JsonObject)?.get("assist") as? JsonObject)?.get("enabled")?.jsonPrimitive?.booleanOrNull
            enabled != false
        }.getOrDefault(true)
    }

    /**
     * False only when [configText] says `{"brain": {"sources": {"<key>": {"enabled": false}}}}` for that source. The owner can
     * switch a knowledge source off for every phone before charging anyone (docs/brain-spec.md, 15, question 2). A missing file,
     * a missing key or text that is not JSON means "on".
     */
    fun sourceAllowed(configText: String?, key: String): Boolean {
        if (configText.isNullOrBlank()) return true
        return runCatching {
            val root = json.parseToJsonElement(configText).jsonObject
            val sources = (root["brain"] as? JsonObject)?.get("sources") as? JsonObject
            val enabled = (sources?.get(key) as? JsonObject)?.get("enabled")?.jsonPrimitive?.booleanOrNull
            enabled != false
        }.getOrDefault(true)
    }
}

@Singleton
class BrainRemoteFlags @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** True unless the saved remote config switches the refresh worker off. Reads one small file. */
    fun refreshAllowed(): Boolean {
        val file = File(context.filesDir, SAVED_CONFIG)
        val text = runCatching { if (file.isFile && file.length() < MAX_CHARS) file.readText() else null }.getOrNull()
        return BrainRemoteConfig.refreshAllowed(text)
    }

    /** True unless the saved remote config switches the assistant's batch jobs off. Reads one small file. */
    fun assistAllowed(): Boolean {
        val file = File(context.filesDir, SAVED_CONFIG)
        val text = runCatching { if (file.isFile && file.length() < MAX_CHARS) file.readText() else null }.getOrNull()
        return BrainRemoteConfig.assistAllowed(text)
    }

    /** True unless the saved remote config switches the open-data source [key] off. Reads one small file. */
    fun sourceAllowed(key: String): Boolean {
        val file = File(context.filesDir, SAVED_CONFIG)
        val text = runCatching { if (file.isFile && file.length() < MAX_CHARS) file.readText() else null }.getOrNull()
        return BrainRemoteConfig.sourceAllowed(text, key)
    }

    private companion object {
        /** `StreamRemoteFlags.CACHE_FILE_NAME`, which is private to that class. */
        const val SAVED_CONFIG = "stream-remote-flags.json"
        const val MAX_CHARS = 64 * 1024
    }
}
