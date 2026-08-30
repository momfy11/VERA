package com.vera.android.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import java.time.Instant

class AppLauncher(private val context: Context) {

    // When VERA calls open_url with a known service URL, open the native app instead
    private val urlToPackage = mapOf(
        "spotify" to "com.spotify.music",
        "instagram" to "com.instagram.android",
        "youtube.com" to "com.google.android.youtube",
        "youtu.be" to "com.google.android.youtube",
        "youtube" to "com.google.android.youtube",
        "gmail" to "com.google.android.gm",
        "maps.google" to "com.google.android.apps.maps",
        "google.com/maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "whatsapp" to "com.whatsapp",
        "tiktok" to "com.zhiliaoapp.musically",
        "facebook.com" to "com.facebook.katana",
        "facebook" to "com.facebook.katana",
        "twitter.com" to "com.twitter.android",
        "x.com" to "com.twitter.android",
        "snapchat" to "com.snapchat.android",
        "netflix" to "com.netflix.mediaclient",
        "telegram" to "org.telegram.messenger",
        "chrome" to "com.android.chrome",
        "camera" to "com.android.camera2",
        "settings" to "com.android.settings",
        "calculator" to "com.google.android.calculator",
        "calendar" to "com.google.android.calendar",
        "clock" to "com.google.android.deskclock",
        "contacts" to "com.google.android.contacts",
        "phone" to "com.google.android.dialer",
        "messages" to "com.google.android.apps.messaging",
        "photos" to "com.google.android.apps.photos",
        "drive" to "com.google.android.apps.docs",
        "keep" to "com.google.android.keep",
        "reddit" to "com.reddit.frontpage",
        "linkedin" to "com.linkedin.android",
        "amazon" to "com.amazon.mShop.android.shopping",
        "uber" to "com.ubercab",
        "gmail" to "com.google.android.gm",
        "discord" to "com.discord",
    )

    fun openUri(uri: String) {
        val lowerUri = uri.lowercase()

        // Match against known URL→package map
        val nativePackage = urlToPackage.entries.firstOrNull { lowerUri.contains(it.key) }?.value
        if (nativePackage != null) {
            if (launchPackage(nativePackage)) return
            // App not installed — fall through to URI attempt
        }

        // Try as a deep link / URL (spotify://, geo:, tel:, https://)
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
        if (parsed != null && parsed.scheme != null && !parsed.scheme!!.contains('.')) {
            val intent = Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
        }

        // Treat uri as a package name directly
        if (!launchPackage(uri)) {
            Toast.makeText(context, "App not found: $uri", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchPackage(packageName: String): Boolean {
        val launchIntent = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull() ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(launchIntent); true }.getOrDefault(false)
    }

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
