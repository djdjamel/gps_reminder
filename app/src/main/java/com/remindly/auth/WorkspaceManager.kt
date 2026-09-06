package com.remindly.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkspaceManager @Inject constructor() {
    
    // null = Mon espace personnel
    // String = Email du collaborateur cible
    private val _currentWorkspaceEmail = MutableStateFlow<String?>(null)
    val currentWorkspaceEmail: StateFlow<String?> = _currentWorkspaceEmail.asStateFlow()

    fun switchToPersonalWorkspace() {
        _currentWorkspaceEmail.value = null
    }

    fun switchToCollaboratorWorkspace(email: String) {
        _currentWorkspaceEmail.value = email.trim().lowercase()
    }
}
