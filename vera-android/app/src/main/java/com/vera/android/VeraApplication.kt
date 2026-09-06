package com.vera.android

import android.app.Application
import com.vera.android.data.prefs.SecurePrefs
import com.vera.android.system.CrashReporter

class VeraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        }.getOrDefault("")

        CrashReporter.install(this, version)

        // Send any crash saved from previous session
        val token = runCatching { SecurePrefs(this).sessionToken }.getOrNull()
        CrashReporter.flushPending(this, token)
    }
}
