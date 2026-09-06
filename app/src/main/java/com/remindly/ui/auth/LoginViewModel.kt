package com.remindly.ui.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remindly.auth.AuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun signInWithGoogle(activityContext: Context) {
        viewModelScope.launch {
            _uiState.value = LoginUiState(isLoading = true, errorMessage = null)
            val result = authManager.signInWithGoogle(activityContext)
            _uiState.value = if (result.isSuccess) {
                LoginUiState(isLoading = false)
            } else {
                LoginUiState(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.message ?: "Erreur inconnue"
                )
            }
        }
    }
}
