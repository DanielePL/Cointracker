package com.cointracker.pro.data.repository

import android.util.Log
import com.cointracker.pro.data.supabase.SupabaseModule
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Repository for storing API keys in Supabase
 * Keys are stored per-user with Row Level Security
 */
class ApiKeyRepository {

    companion object {
        private const val TAG = "ApiKeyRepository"
        private const val TABLE = "user_api_keys"
    }

    private val database = SupabaseModule.database
    private val auth = SupabaseModule.client.auth

    /**
     * Save API keys to Supabase
     */
    suspend fun saveApiKeys(
        apiKey: String,
        secretKey: String,
        isTestnet: Boolean = false
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val userId = auth.currentUserOrNull()?.id
                ?: return@withContext Result.failure(Exception("Not logged in"))

            val data = ApiKeyData(
                userId = userId,
                exchange = "binance",
                apiKey = apiKey,
                secretKey = secretKey,
                isTestnet = isTestnet
            )

            // Upsert - insert or update if exists
            database.from(TABLE).upsert(data)

            Log.d(TAG, "API keys saved to Supabase")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save API keys", e)
            Result.failure(e)
        }
    }

    /**
     * Get API keys from Supabase
     */
    suspend fun getApiKeys(): Result<ApiKeyData?> = withContext(Dispatchers.IO) {
        try {
            val userId = auth.currentUserOrNull()?.id
                ?: return@withContext Result.failure(Exception("Not logged in"))

            val result = database.from(TABLE)
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("exchange", "binance")
                    }
                }
                .decodeSingleOrNull<ApiKeyData>()

            Log.d(TAG, "API keys retrieved: ${result != null}")
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get API keys", e)
            Result.failure(e)
        }
    }

    /**
     * Delete API keys from Supabase
     */
    suspend fun deleteApiKeys(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val userId = auth.currentUserOrNull()?.id
                ?: return@withContext Result.failure(Exception("Not logged in"))

            database.from(TABLE).delete {
                filter {
                    eq("user_id", userId)
                    eq("exchange", "binance")
                }
            }

            Log.d(TAG, "API keys deleted")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete API keys", e)
            Result.failure(e)
        }
    }

    /**
     * Check if API keys exist
     */
    suspend fun hasApiKeys(): Boolean {
        return getApiKeys().getOrNull() != null
    }
}

@Serializable
data class ApiKeyData(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String,
    val exchange: String = "binance",
    @SerialName("api_key")
    val apiKey: String,
    @SerialName("secret_key")
    val secretKey: String,
    @SerialName("is_testnet")
    val isTestnet: Boolean = false,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)
