package com.theveloper.pixelplay.data.ai.claude.taizo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

@Serializable
data class StoredTaizoSource(val url: String, val title: String)

@Serializable
data class StoredTaizoMessage(
    /** "user" or "assistant". */
    val role: String,
    val text: String,
    val sources: List<StoredTaizoSource> = emptyList(),
)

@Serializable
data class StoredTaizoChat(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<StoredTaizoMessage>,
)

@Serializable
private data class StoredTaizoChats(val chats: List<StoredTaizoChat> = emptyList())

/**
 * The last few Ask Taizo conversations, on the phone only (filesDir/taizo/chats.json). Only what
 * the chat shows is kept (the words, and the pages Claude cited); a reopened chat continues from
 * that text. Never part of a backup.
 */
@Singleton
class TaizoChatStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: File get() = File(File(context.noBackupFilesDir, "taizo"), "chats.json")

    suspend fun load(): List<StoredTaizoChat> = mutex.withLock { read() }

    /** Saves [chat] as the newest and keeps the [MAX_CHATS] most recent. */
    suspend fun save(chat: StoredTaizoChat) {
        if (chat.messages.isEmpty()) return
        mutex.withLock {
            val chats = (listOf(chat) + read().filter { it.id != chat.id })
                .sortedByDescending { it.updatedAt }
                .take(MAX_CHATS)
            write(chats)
        }
    }

    suspend fun delete(id: String) = mutex.withLock { write(read().filter { it.id != id }) }

    private suspend fun read(): List<StoredTaizoChat> = withContext(Dispatchers.IO) {
        runCatching {
            val f = file
            if (!f.exists()) emptyList() else json.decodeFromString(StoredTaizoChats.serializer(), f.readText()).chats
        }.onFailure { Timber.tag(TAG).w(it, "Couldn't read saved chats") }.getOrDefault(emptyList())
    }

    private suspend fun write(chats: List<StoredTaizoChat>) = withContext(Dispatchers.IO) {
        runCatching {
            val f = file
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "chats.json.tmp")
            tmp.writeText(json.encodeToString(StoredTaizoChats.serializer(), StoredTaizoChats(chats)))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }.onFailure { Timber.tag(TAG).w(it, "Couldn't save chats") }
        Unit
    }

    companion object {
        const val MAX_CHATS = 5
        private const val TAG = "TaizoChatStore"

        /**
         * The API history a reopened chat continues from: its text, as alternating user and
         * assistant messages that start with the user. Pure (TaizoChatStoreTest).
         */
        fun historyFrom(messages: List<StoredTaizoMessage>): List<JsonObject> {
            val merged = ArrayList<Pair<String, StringBuilder>>()
            for (message in messages) {
                if (message.text.isBlank() || (message.role != "user" && message.role != "assistant")) continue
                if (merged.isEmpty() && message.role != "user") continue
                val last = merged.lastOrNull()
                if (last != null && last.first == message.role) {
                    last.second.append("\n\n").append(message.text)
                } else {
                    merged += message.role to StringBuilder(message.text)
                }
            }
            return merged.map { (role, text) ->
                buildJsonObject {
                    put("role", role)
                    put("content", text.toString())
                }
            }
        }
    }
}
