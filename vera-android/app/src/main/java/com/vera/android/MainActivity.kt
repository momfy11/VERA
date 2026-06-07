package com.vera.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vera.android.data.prefs.SecurePrefs
import com.vera.android.ui.login.LoginScreen
import com.vera.android.ui.main.MainScreen
import com.vera.android.ui.main.OnboardingScreen
import com.vera.android.ui.memories.MemoriesScreen
import com.vera.android.ui.settings.SettingsScreen
import com.vera.android.ui.suggestions.SuggestionsScreen
import com.vera.android.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = SecurePrefs(this)

        setContent {
            MaterialTheme {
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
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onLogout = {
                                val vm: MainViewModel = viewModel(navController.getBackStackEntry("main"))
                                vm.logout()
                                prefs.clear()
                                navController.navigate("login") { popUpTo(0) { inclusive = true } }
                            }
                        )
                    }
                    composable("memories") {
                        MemoriesScreen(onBack = { navController.popBackStack() })
                    }
                    composable("suggestions") {
                        SuggestionsScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
