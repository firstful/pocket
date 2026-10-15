package com.sutliff.pocket.data

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Durable outbox for messages composed while offline (or while a backlog existed).
 *
 * Stored as JSON in filesDir/outbox.json — same storage style as the session store, so queued
 * messages survive app restarts and process death. Writes are atomic (tmp file + rename) so a
 * crash mid-write can never corrupt/lose the queue.
 */
class OutboxStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "outbox.json")

    @Synchronized
    fun load(): List<QueuedMessage> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<QueuedMessage>>(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun entriesFor(sessionId: String): List<QueuedMessage> =
        load().filter { it.sessionId == sessionId }.sortedBy { it.timestamp }

    @Synchronized
    fun add(message: QueuedMessage) {
        write(load() + message)
    }

    @Synchronized
    fun remove(message: QueuedMessage) {
        write(
            load().filterNot {
                it.timestamp == message.timestamp && it.sessionId == message.sessionId && it.text == message.text
            }
        )
    }

    @Synchronized
    fun removeForSession(sessionId: String) {
        write(load().filterNot { it.sessionId == sessionId })
    }

    private fun write(messages: List<QueuedMessage>) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(messages))
            if (!tmp.renameTo(file)) {
                // POSIX rename replaces atomically; fall back to replace-on-delete.
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            // Best effort: keep the previous file rather than throwing into UI paths.
        }
    }
}
