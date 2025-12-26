package com.cointracker.pro.data.supabase

import android.util.Log
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.user.UserInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for Supabase Authentication
 */
class SupabaseAuthRepository {

    private val auth = SupabaseModule.auth

    companion object {
        private const val TAG = "SupabaseAuth"
    }

    /**
     * Sign up with email and password
     */
    suspend fun signUp(email: String, password: String): Result<UserInfo> {
        return try {
            auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }

            val user = auth.currentUserOrNull()
            if (user != null) {
                Log.d(TAG, "Sign up successful: ${user.id}")
                Result.success(user)
            } else {
                Result.failure(Exception("Sign up failed - no user returned"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sign up failed", e)
            Result.failure(e)
        }
    }

    /**
     * Sign in with email and password
     */
    suspend fun signIn(email: String, password: String): Result<UserInfo> {
        return try {
            auth.signInWith(Email) {
                this.email = email
                this.password = password
            }

            val user = auth.currentUserOrNull()
            if (user != null) {
                Log.d(TAG, "Sign in successful: ${user.id}")
                Result.success(user)
            } else {
                Result.failure(Exception("Sign in failed - no user returned"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sign in failed", e)
            Result.failure(e)
        }
    }

    /**
     * Sign out the current user
     */
    suspend fun signOut(): Result<Unit> {
        return try {
            auth.signOut()
            Log.d(TAG, "Sign out successful")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sign out failed", e)
            Result.failure(e)
        }
    }

    /**
     * Get the current user (nullable)
     */
    fun getCurrentUser(): UserInfo? {
        return auth.currentUserOrNull()
    }

    /**
     * Check if user is logged in
     */
    fun isLoggedIn(): Boolean {
        return auth.currentUserOrNull() != null
    }

    /**
     * Observe authentication state changes
     */
    fun observeAuthState(): Flow<UserInfo?> {
        return auth.sessionStatus.map { status ->
            when (status) {
                is io.github.jan.supabase.auth.status.SessionStatus.Authenticated -> {
                    auth.currentUserOrNull()
                }
                else -> null
            }
        }
    }

    /**
     * Get the current access token (for API calls)
     */
    suspend fun getAccessToken(): String? {
        return auth.currentAccessTokenOrNull()
    }

    /**
     * Send password reset email
     */
    suspend fun resetPassword(email: String): Result<Unit> {
        return try {
            auth.resetPasswordForEmail(email)
            Log.d(TAG, "Password reset email sent to $email")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Password reset failed", e)
            Result.failure(e)
        }
    }

    /**
     * Update user password
     */
    suspend fun updatePassword(newPassword: String): Result<Unit> {
        return try {
            auth.updateUser {
                password = newPassword
            }
            Log.d(TAG, "Password updated successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Password update failed", e)
            Result.failure(e)
        }
    }
}
