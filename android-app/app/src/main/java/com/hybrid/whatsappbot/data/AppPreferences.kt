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

    fun hasPreviousPostInGroup(groupName: String): Boolean {
        val key = buildGroupSentKey(groupName)
        return prefs.getBoolean(key, false)
    }

    fun getLastSentCaptionSnippet(groupName: String): String {
        val key = buildGroupCaptionKey(groupName)
        return prefs.getString(key, "") ?: ""
    }

    fun markPostSentInGroup(groupName: String, caption: String) {
        val sentKey = buildGroupSentKey(groupName)
        val captionKey = buildGroupCaptionKey(groupName)
        val snippet = caption.trim().lineSequence().firstOrNull()?.take(40)?.trim()
            ?: caption.trim().take(40)
        prefs.edit()
            .putBoolean(sentKey, true)
            .putString(captionKey, snippet)
            .apply()
    }

    private fun buildGroupSentKey(groupName: String): String =
        "group_has_post_${groupName.trim().lowercase()}"

    private fun buildGroupCaptionKey(groupName: String): String =
        "group_last_caption_${groupName.trim().lowercase()}"

    companion object {
        private const val PREFS_NAME = "hybrid_whatsapp_bot_prefs"
        private const val KEY_BACKEND_URL = "backend_base_url"
        const val DEFAULT_BACKEND_URL = "http://10.0.2.2:3000"
    }
}
