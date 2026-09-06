package com.beeper.lightos

import android.util.Log
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull

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

        // The pusher is registered as event_id_only, so the payload carries no text.
        // Post a placeholder anyway to see whether LightOS surfaces it at all.
        BeeperRepository.appContext?.let { context ->
            val result = BeeperNotifications.post(context, "Chat", "New message")
            Log.d(TAG, "Notification attempt from push: $result")
        } ?: Log.e(TAG, "appContext is null, cannot post a notification")

        try {
            BeeperRepository.forceBackgroundSync()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling push notification", e)
        }
    }
}
