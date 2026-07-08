package com.vera.android

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vera.android.data.prefs.SecurePrefs
import com.vera.android.ui.login.LoginScreen
import com.vera.android.ui.main.HelpScreen
import com.vera.android.ui.main.MainScreen
import com.vera.android.ui.main.OnboardingScreen
import com.vera.android.ui.memories.MemoriesScreen
import com.vera.android.ui.settings.SettingsScreen
import com.vera.android.ui.settings.WakeWordTrainingScreen
import com.vera.android.ui.suggestions.SuggestionsScreen
import com.vera.android.viewmodel.MainViewModel

private val VeraDarkColors = darkColorScheme(
    primary = Color(0xFFFF6D00),
    onPrimary = Color.White,
    background = Color(0xFF0A0A12),
    surface = Color(0xFF13131F),
    onBackground = Color(0xFFF0F0F0),
    onSurface = Color(0xFFF0F0F0),
    secondary = Color(0xFF6750A4),
    onSecondary = Color.White,
)

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled in OnboardingScreen */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Request mic at launch so SpeechRecognizer works immediately
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
            ))
        }
        val prefs = try {
            SecurePrefs(this)
        } catch (_: Exception) {
            // Keystore corrupted (common after Android update / fingerprint change).
            // Delete master key + clear encrypted file, then recreate.
            runCatching {
                val ks = java.security.KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
                ks.deleteEntry(androidx.security.crypto.MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
            getSharedPreferences("vera_secure_prefs", MODE_PRIVATE).edit().clear().apply()
            SecurePrefs(this)
        }

        setContent {
            MaterialTheme(colorScheme = VeraDarkColors) {
                val navController = rememberNavController()
                val startDest = when {
                    prefs.sessionToken == null -> "login"
                    !prefs.onboardingDone -> "onboarding"
                    else -> "main"
                }

                NavHost(navController, startDestination = startDest) {
                    composable("login") {
                        LoginScreen(onLoggedIn = {
                            navController.navigate("onboarding") { popUpTo("login") { inclusive = true } }
                        })
                    }
                    composable("onboarding") {
                        OnboardingScreen(onDone = {
                            prefs.onboardingDone = true
                            navController.navigate("main") { popUpTo("onboarding") { inclusive = true } }
                        })
                    }
                    composable("main") {
                        val vm: MainViewModel = viewModel()
                        MainScreen(
                            vm = vm,
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenMemories = { navController.navigate("memories") },
                            onOpenSuggestions = { navController.navigate("suggestions") },
                            onOpenHelp = { navController.navigate("help") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onLogout = {
                                prefs.clear()
                                navController.navigate("login") { popUpTo(0) { inclusive = true } }
                            },
                            onTrainWakeWord = { navController.navigate("wakeword_training") },
                        )
                    }
                    composable("wakeword_training") {
                        WakeWordTrainingScreen(onBack = { navController.popBackStack() })
                    }
                    composable("memories") {
                        MemoriesScreen(onBack = { navController.popBackStack() })
                    }
                    composable("suggestions") {
                        SuggestionsScreen(onBack = { navController.popBackStack() })
                    }
                    composable("help") {
                        HelpScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
