package com.vera.android.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vera.android.data.api.VeraApi
import com.vera.android.data.prefs.SecurePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class LoginUiState(
    val email: String = "",
    val displayName: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
)

class LoginViewModel(app: Application) : AndroidViewModel(app) {
    private val api = VeraApi(OkHttpClient())
    private val prefs = SecurePrefs(app)

    private val _ui = MutableStateFlow(LoginUiState())
    val ui: StateFlow<LoginUiState> = _ui.asStateFlow()

    fun onEmailChange(v: String) = _ui.value.let { _ui.value = it.copy(email = v) }
    fun onNameChange(v: String) = _ui.value.let { _ui.value = it.copy(displayName = v) }

    fun login() {
        val email = _ui.value.email.trim()
        val name = _ui.value.displayName.trim().ifBlank { email.substringBefore("@") }
        if (email.isBlank()) { _ui.value = _ui.value.copy(error = "Email required"); return }
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { api.login(email, name) }
                .onSuccess { resp ->
                    prefs.sessionToken = resp.session_token
                    prefs.displayName = name
                    _ui.value = _ui.value.copy(loading = false, success = true)
                }
                .onFailure { e ->
                    _ui.value = _ui.value.copy(loading = false, error = e.message ?: "Login failed")
                }
        }
    }
}
