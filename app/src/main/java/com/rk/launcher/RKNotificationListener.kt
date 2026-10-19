package com.rk.launcher

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional notification bridge. Android only binds this service after the user explicitly
 * enables notification access in system Settings. Items remain local to this device.
 */
class RKNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        try {
            val extras = sbn.notification.extras ?: return
            val title = (extras.getCharSequence(Notification.EXTRA_TITLE) ?: "Notifikasi").toString().trim()
            val text = (
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
                ).toString().trim()
            if (title.isBlank() && text.isBlank()) return

            synchronized(STORE_LOCK) {
                val prefs = getSharedPreferences("rk_launcher", MODE_PRIVATE)
                val old = try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
                val updated = JSONArray()
                for (i in 0 until old.length()) {
                    val item = old.optJSONObject(i) ?: continue
                    if (item.optString("key") != sbn.key) updated.put(item)
                }
                updated.put(JSONObject().apply {
                    put("key", sbn.key)
                    put("package", sbn.packageName)
                    put("title", title.take(160))
                    put("text", text.take(500))
                    put("when", sbn.postTime)
                })
                val bounded = JSONArray()
                val start = (updated.length() - MAX_ITEMS).coerceAtLeast(0)
                for (i in start until updated.length()) bounded.put(updated.getJSONObject(i))
                prefs.edit().putString(KEY, bounded.toString()).apply()
            }
        } catch (_: Exception) {
            // A malformed third-party notification must never crash the launcher/service.
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        try {
            synchronized(STORE_LOCK) {
                val prefs = getSharedPreferences("rk_launcher", MODE_PRIVATE)
                val old = JSONArray(prefs.getString(KEY, "[]"))
                val updated = JSONArray()
                for (i in 0 until old.length()) {
                    val item = old.optJSONObject(i) ?: continue
                    if (item.optString("key") != sbn.key) updated.put(item)
                }
                prefs.edit().putString(KEY, updated.toString()).apply()
            }
        } catch (_: Exception) {
            // Ignore damaged local notification state; the launcher itself remains usable.
        }
    }

    companion object {
        const val KEY = "notification_items"
        const val MAX_ITEMS = 40
        val STORE_LOCK = Any()
    }
}
