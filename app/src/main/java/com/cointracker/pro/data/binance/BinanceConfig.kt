package com.cointracker.pro.data.binance

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for Binance API credentials
 * Uses EncryptedSharedPreferences for security
 */
class BinanceConfig private constructor(context: Context) {

    companion object {
        private const val TAG = "BinanceConfig"
        private const val PREFS_NAME = "binance_secure_prefs"
        private const val KEY_API_KEY = "binance_api_key"
        private const val KEY_SECRET = "binance_secret_key"
        private const val KEY_TESTNET = "binance_use_testnet"

        const val BASE_URL = "https://api.binance.com"
        const val TESTNET_URL = "https://testnet.binance.vision"
        const val WS_URL = "wss://stream.binance.com:9443/ws"
        const val TESTNET_WS_URL = "wss://testnet.binance.vision/ws"

        @Volatile
        private var instance: BinanceConfig? = null

        fun getInstance(context: Context): BinanceConfig {
            return instance ?: synchronized(this) {
                instance ?: BinanceConfig(context.applicationContext).also { instance = it }
            }
        }
    }

    private val securePrefs: SharedPreferences

    init {
        securePrefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences failed, using fallback", e)
            context.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
        }
    }

    /**
     * Save API credentials securely
     * Uses commit() instead of apply() to ensure synchronous write
     */
    fun saveCredentials(apiKey: String, secretKey: String): Boolean {
        return try {
            val success = securePrefs.edit()
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_SECRET, secretKey)
                .commit()
            Log.d(TAG, "Credentials saved: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save credentials", e)
            false
        }
    }

    /**
     * Get stored API key
     */
    fun getApiKey(): String? {
        return try {
            securePrefs.getString(KEY_API_KEY, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get API key", e)
            null
        }
    }

    /**
     * Get stored secret key
     */
    fun getSecretKey(): String? {
        return try {
            securePrefs.getString(KEY_SECRET, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get secret key", e)
            null
        }
    }

    /**
     * Check if credentials are configured
     */
    fun hasCredentials(): Boolean {
        return !getApiKey().isNullOrBlank() && !getSecretKey().isNullOrBlank()
    }

    /**
     * Clear stored credentials
     */
    fun clearCredentials() {
        Log.d(TAG, "Clearing credentials...")
        securePrefs.edit()
            .remove(KEY_API_KEY)
            .remove(KEY_SECRET)
            .commit()  // Use commit for synchronous clear
        Log.d(TAG, "Credentials cleared")
    }

    /**
     * Enable/disable testnet mode
     */
    fun setTestnetMode(enabled: Boolean) {
        securePrefs.edit()
            .putBoolean(KEY_TESTNET, enabled)
            .commit()
    }

    /**
     * Check if testnet mode is enabled
     */
    fun isTestnetMode(): Boolean {
        return securePrefs.getBoolean(KEY_TESTNET, false)
    }

    /**
     * Get the appropriate base URL
     */
    fun getBaseUrl(): String {
        return if (isTestnetMode()) TESTNET_URL else BASE_URL
    }

    /**
     * Get the appropriate WebSocket URL
     */
    fun getWsUrl(): String {
        return if (isTestnetMode()) TESTNET_WS_URL else WS_URL
    }
}
