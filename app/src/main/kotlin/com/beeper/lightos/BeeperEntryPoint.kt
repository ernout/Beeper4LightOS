package com.beeper.lightos

import android.util.Log
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.json.contentOrNull

@EntryPoint
object BeeperEntryPoint : LightEntryPoint {
    private const val TAG = "BeeperEntryPoint"

    override val enablePushNotifications = true

    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {
        Log.d(TAG, "onToolCreate called")
        serverData.filterNotNull().collect { data ->
            data.pushCredentials?.let { creds ->
                Log.d(TAG, "Received UnifiedPush endpoint: ${creds.pushEndpoint}")
                try {
                    BeeperRepository.registerPushEndpoint(creds.pushEndpoint)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register push endpoint", e)
                }
            }
        }
    }

    override suspend fun onPushNotification(data: ByteArray) {
        val payloadStr = String(data)
        Log.d(TAG, "Received push notification: $payloadStr")

        val roomId = roomIdOf(payloadStr)
        val context = BeeperRepository.appContext
        if (context == null) {
            Log.e(TAG, "appContext is null, cannot post a notification")
        }

        // The cached chat list answers instantly, which keeps the buzz close to the
        // message. An unknown room waits for the sync and asks the client after.
        val cached = context?.let { roomId?.let { id -> BeeperChatListCache.favoriteFlag(it, id) } }
        if (context != null && cached == true) {
            alert(context)
        }

        try {
            BeeperRepository.forceBackgroundSync()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling push notification", e)
        }

        if (context != null && cached == null) {
            when (val favorite = roomId?.let { BeeperRepository.isFavoriteRoom(it) }) {
                true -> alert(context)
                false -> Log.d(TAG, "$roomId is not a favourite, staying quiet")
                null -> Log.d(TAG, "Cannot tell whether $roomId is a favourite, staying quiet")
            }
        } else if (cached == false) {
            Log.d(TAG, "$roomId is not a favourite, staying quiet")
        }
    }

    /** Sound and vibration are the whole alert; LightOS draws nothing for it. */
    private fun alert(context: android.content.Context) {
        val result = BeeperNotifications.post(context, "Chat", "New message")
        Log.d(TAG, "Notification attempt from push: $result")
    }

    /** The gateway forwards the room id; the pusher's own payload has no text. */
    private fun roomIdOf(payload: String): String? = try {
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .parseToJsonElement(payload)
            .let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("room_id")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.contentOrNull
    } catch (e: Exception) {
        Log.w(TAG, "Push payload is not the JSON we send: $payload")
        null
    }
}
