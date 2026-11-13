package com.hybrid.whatsappbot.data

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var backendBaseUrl: String
        get() = prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL
        set(value) {
            val sanitized = value.trim().removeSuffix("/")
            prefs.edit().putString(KEY_BACKEND_URL, sanitized).apply()
        }

    companion object {
        private const val PREFS_NAME = "hybrid_whatsapp_bot_prefs"
        private const val KEY_BACKEND_URL = "backend_base_url"
        // Default local LAN / emulator bridge URL; configurable inside the app UI
        const val DEFAULT_BACKEND_URL = "http://10.0.2.2:3000"
    }
}
