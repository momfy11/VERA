package com.vera.android

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.security.NetworkSecurityPolicy
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@SmallTest
@RunWith(AndroidJUnit4::class)
class VeraSecurityTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    // ── Manifest flags ──────────────────────────────────────────────────────

    @Test
    fun manifest_usesCleartextTraffic_is_false() {
        val cleartextPermitted = NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted
        assertFalse(
            "Cleartext HTTP traffic is permitted — usesCleartextTraffic should be false",
            cleartextPermitted
        )
    }

    @Test
    fun manifest_declares_record_audio_permission() {
        val pi: PackageInfo = ctx.packageManager.getPackageInfo(
            ctx.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val perms = pi.requestedPermissions?.toList() ?: emptyList()
        assertTrue(
            "RECORD_AUDIO permission not declared in manifest",
            perms.contains("android.permission.RECORD_AUDIO"),
        )
    }

    // ── Shared preferences plaintext audit ──────────────────────────────────

    @Test
    fun no_plaintext_session_token_in_shared_prefs_files() {
        val prefsDir = File(ctx.dataDir, "shared_prefs")
        if (!prefsDir.exists()) return

        val secretPatterns = listOf(
            "session_token",
            "Bearer ",
            "api_key",
            "Authorization",
        )

        val violations = mutableListOf<String>()
        prefsDir.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .forEach { file ->
                val content = runCatching { file.readText() }.getOrDefault("")
                secretPatterns.forEach { pattern ->
                    if (content.contains(pattern, ignoreCase = false)) {
                        violations += "${file.name}: contains '$pattern' in plaintext"
                    }
                }
            }

        assertTrue(
            "Plaintext secrets found in SharedPreferences:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun encrypted_prefs_file_does_not_expose_display_name_plaintext() {
        val prefsDir = File(ctx.dataDir, "shared_prefs")
        if (!prefsDir.exists()) return

        val encFile = prefsDir.listFiles()
            ?.firstOrNull { it.name.contains(ctx.packageName) || it.name.contains("vera") }
            ?: return

        val content = runCatching { encFile.readText() }.getOrDefault("")
        assertFalse(
            "display_name key visible in plaintext inside ${encFile.name} — EncryptedSharedPreferences not working",
            content.contains("display_name"),
        )
    }

    // ── Network scheme audit ─────────────────────────────────────────────────

    @Test
    fun foreground_service_wake_url_is_wss() {
        val fields = Class.forName("com.vera.android.audio.VeraForegroundService")
            .declaredFields
        val urlField = fields.firstOrNull { it.name == "WS_WAKE_URL" }
        if (urlField != null) {
            urlField.isAccessible = true
            val url = urlField.get(null) as? String ?: return
            assertTrue(
                "WS_WAKE_URL must use wss://, found: $url",
                url.startsWith("wss://"),
            )
        }
    }

    // ── SDK version ──────────────────────────────────────────────────────────

    @Test
    fun min_sdk_is_at_least_26() {
        val minSdk = ctx.packageManager
            .getApplicationInfo(ctx.packageName, 0)
            .minSdkVersion
        assertTrue(
            "minSdk $minSdk < 26 — EncryptedSharedPreferences requires API 23+, AES256_GCM recommended on 26+",
            minSdk >= 26,
        )
    }
}
