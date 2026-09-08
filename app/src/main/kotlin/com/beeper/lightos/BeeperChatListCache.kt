package com.beeper.lightos

import android.util.Log
import kotlinx.serialization.json.Json

/**
 * The last rendered chat list, kept on disk.
 *
 * Trixnity's Room database already holds the Matrix state, but rebuilding a row from it
 * costs a timeline walk per room and a display-name lookup per hero, and rooms stay
 * invisible until their `m.tag` account data has been read back. Storing the finished
 * rows means the list is on screen at the first frame and live data only corrects it.
 */
object BeeperChatListCache {
    private const val TAG = "BeeperChatListCache"
    private const val KEY = "chat_list_v1"
    private const val MAX_ENTRIES = 40

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences("beeper_prefs", android.content.Context.MODE_PRIVATE)

    fun load(context: android.content.Context): List<RoomSummary> {
        val stored = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<RoomSummary>>(stored)
        } catch (e: Exception) {
            Log.w(TAG, "could not read cached chat list, starting empty", e)
            emptyList()
        }
    }

    /** Whether a room is a favourite according to the cached rows, null if unknown. */
    fun favoriteFlag(context: android.content.Context, roomId: String): Boolean? =
        load(context).firstOrNull { it.roomId == roomId }?.isFavorite

    fun save(context: android.content.Context, summaries: List<RoomSummary>) {
        try {
            val trimmed = summaries.sortedByDescending { it.lastTimestamp }.take(MAX_ENTRIES)
            prefs(context).edit().putString(KEY, json.encodeToString(trimmed)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "could not write cached chat list", e)
        }
    }
}
