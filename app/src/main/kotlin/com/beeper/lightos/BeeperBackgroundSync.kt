package com.beeper.lightos

import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult

@LightJob("beeper-sync")
val backgroundSyncJob: LightJobHandler = { context, _ ->
    try {
        val appContext = BeeperRepository.appContext
        if (appContext == null) {
            LightJobResult.Error(mapOf("error" to "App not initialized yet. Please open Beeper to start sync."))
        } else {
            BeeperRepository.syncOnce(appContext)
            LightJobResult.Success()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        LightJobResult.Error(mapOf("error" to (e.message ?: "Unknown error")))
    }
}

/**
 * Posts a notification after a delay, so it can arrive with the tool closed.
 * Runs through WorkManager, which keeps going after you leave the app — unless
 * LightOS force-stops the package, which is itself worth knowing.
 */
@LightJob("beeper-test-notification")
val testNotificationJob: LightJobHandler = { _, input ->
    val delaySeconds = input["delaySeconds"]?.toLongOrNull() ?: 20L
    android.util.Log.d("BeeperNotifications", "delayed test: waiting ${delaySeconds}s")
    kotlinx.coroutines.delay(delaySeconds * 1_000)

    val appContext = BeeperRepository.appContext
    if (appContext == null) {
        android.util.Log.e("BeeperNotifications", "delayed test: no app context")
        LightJobResult.Error(mapOf("error" to "No app context"))
    } else {
        val result = BeeperNotifications.post(appContext, "Chat", "Delayed test notification")
        android.util.Log.d("BeeperNotifications", "delayed test fired: $result")
        LightJobResult.Success(mapOf("result" to result.name))
    }
}
