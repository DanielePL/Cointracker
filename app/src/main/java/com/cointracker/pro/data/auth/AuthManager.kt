package com.cointracker.pro.data.auth

import android.content.Context
import android.content.SharedPreferences
import com.cointracker.pro.data.api.ApiClient
import com.cointracker.pro.data.models.AuthToken
import com.cointracker.pro.data.models.LoginRequest
import com.cointracker.pro.data.models.RegisterRequest
import com.cointracker.pro.data.models.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages user authentication state and tokens
 */
class AuthManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val api = ApiClient.api

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    init {
        // Check if we have a stored token
        val token = getStoredToken()
        _isAuthenticated.value = token != null
    }

    suspend fun login(username: String, password: String): Result<AuthToken> {
        return try {
            val request = LoginRequest(username, password)
            val token = api.login(request)
            saveToken(token)
            _isAuthenticated.value = true

            // Fetch user info
            val user = api.getCurrentUser("Bearer ${token.accessToken}")
            _currentUser.value = user

            Result.success(token)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun register(username: String, password: String, email: String? = null): Result<User> {
        return try {
            val request = RegisterRequest(username, password, email)
            val user = api.register(request)
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshUserInfo(): Result<User> {
        val token = getStoredToken() ?: return Result.failure(Exception("Not authenticated"))
        return try {
            val user = api.getCurrentUser("Bearer $token")
            _currentUser.value = user
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun logout() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
        _isAuthenticated.value = false
        _currentUser.value = null
    }

    fun getStoredToken(): String? {
        val token = prefs.getString(KEY_TOKEN, null)
        val expiresAt = prefs.getString(KEY_EXPIRES_AT, null)

        // TODO: Check if token is expired
        return token
    }

    fun getAuthHeader(): String? {
        val token = getStoredToken() ?: return null
        return "Bearer $token"
    }

    private fun saveToken(token: AuthToken) {
        prefs.edit()
            .putString(KEY_TOKEN, token.accessToken)
            .putString(KEY_EXPIRES_AT, token.expiresAt)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "cointracker_auth"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_EXPIRES_AT = "expires_at"

        @Volatile
        private var INSTANCE: AuthManager? = null

        fun getInstance(context: Context): AuthManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuthManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
