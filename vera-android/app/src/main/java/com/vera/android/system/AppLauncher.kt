package com.vera.android.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import java.time.Instant

class AppLauncher(private val context: Context) {

    // When VERA calls open_url with a known service URL, open the native app instead
    private val urlToPackage = mapOf(
        "spotify" to "com.spotify.music",
        "instagram" to "com.instagram.android",
        "youtube.com" to "com.google.android.youtube",
        "youtu.be" to "com.google.android.youtube",
        "gmail" to "com.google.android.gm",
        "maps.google" to "com.google.android.apps.maps",
        "google.com/maps" to "com.google.android.apps.maps",
        "whatsapp" to "com.whatsapp",
        "tiktok" to "com.zhiliaoapp.musically",
        "facebook.com" to "com.facebook.katana",
        "twitter.com" to "com.twitter.android",
        "x.com" to "com.twitter.android",
        "snapchat" to "com.snapchat.android",
        "netflix" to "com.netflix.mediaclient",
    )

    fun openUri(uri: String) {
        runCatching {
            // Check if URL matches a known native app
            val lowerUri = uri.lowercase()
            val nativePackage = urlToPackage.entries.firstOrNull { lowerUri.contains(it.key) }?.value

            if (nativePackage != null && isInstalled(nativePackage)) {
                launchPackage(nativePackage)
                return
            }

            // Try as Intent URI directly (handles deep links like spotify://, geo:, tel:)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                // Try as package name
                launchPackage(uri)
            }
        }
    }

    fun launchPackage(packageName: String) {
        runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
        }
    }

    private fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    fun scheduleReminder(timeIso: String, text: String) {
        runCatching {
            val triggerMs = Instant.parse(timeIso).toEpochMilli()
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra(ReminderReceiver.EXTRA_TEXT, text)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                text.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            context.getSystemService(AlarmManager::class.java)
                .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    fun openNotificationListenerSettings() {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openAccessibilitySettings() {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
