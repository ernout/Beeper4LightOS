package com.beeper.lightos

/**
 * LightOS answers a permission request for anything it won't hand to a tool with a
 * full-screen "Not allowed". Asking on every screen open puts that in your face
 * every visit, so ask once per install and let the feature stay off after that.
 */
object BeeperPermissions {
    fun shouldAsk(permission: String): Boolean {
        val prefs = BeeperRepository.appContext?.getSharedPreferences(
            "beeper_prefs", android.content.Context.MODE_PRIVATE
        ) ?: return false
        val key = "asked_$permission"
        if (prefs.getBoolean(key, false)) return false
        prefs.edit().putBoolean(key, true).apply()
        return true
    }
}
