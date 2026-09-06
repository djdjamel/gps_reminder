package com.remindly.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.remindly.data.remote.FirestoreDataSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestoreDataSource: FirestoreDataSource
) {
    private val auth = FirebaseAuth.getInstance()
    private val credentialManager = CredentialManager.create(context)

    // TODO: Mettre le vrai Web Client ID (Google Cloud Console)
    private val webClientId = "385437587284-7qtbie5o8s6cre1jv4vd0ibh3g3eup6m.apps.googleusercontent.com"

    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val currentUserState: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    val currentUser get() = auth.currentUser

    init {
        auth.addAuthStateListener { firebaseAuth ->
            _currentUser.value = firebaseAuth.currentUser
        }
    }


    suspend fun signInWithGoogle(activityContext: Context): Result<Unit> {
        return try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(true)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            // Utiliser le contexte d'Activity (obligatoire sur certains appareils Samsung/Xiaomi)
            val result = credentialManager.getCredential(activityContext, request)
            val credential = result.credential

            // Extraire le GoogleIdTokenCredential (peut être direct ou encapsulé dans un CustomCredential)
            val googleIdTokenCredential: GoogleIdTokenCredential? = when {
                credential is GoogleIdTokenCredential -> credential
                credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ->
                    GoogleIdTokenCredential.createFrom(credential.data)
                else -> null
            }

            if (googleIdTokenCredential != null) {
                val idToken = googleIdTokenCredential.idToken
                val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)

                if (auth.currentUser?.isAnonymous == true) {
                    auth.currentUser?.linkWithCredential(firebaseCredential)?.await()
                } else {
                    auth.signInWithCredential(firebaseCredential).await()
                }

                // Enregistrer le profil dans Firestore
                auth.currentUser?.let { user ->
                    if (user.email != null) {
                        firestoreDataSource.saveUserProfile(
                            uid = user.uid,
                            email = user.email!!,
                            name = user.displayName
                        )
                    }
                }

                Result.success(Unit)
            } else {
                Result.failure(Exception("Type de credential non supporté : ${credential.type}"))
            }
        } catch (e: GetCredentialException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signOut() {
        auth.signOut()
    }
}
