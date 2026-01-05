package com.cointracker.pro.data.repository

import android.util.Log
import com.cointracker.pro.data.supabase.BullrunScannerResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Repository for Bullrun Scanner API calls
 */
class BullrunRepository {

    companion object {
        private const val TAG = "BullrunRepository"
        private const val BASE_URL = "https://cointracker-or1f.onrender.com"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * Fetch top bullrun coins from backend
     */
    suspend fun getBullrunCoins(limit: Int = 10): Result<BullrunScannerResponse> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/api/v3/analysis/bullrun-scanner?limit=$limit")
            val connection = url.openConnection() as HttpsURLConnection

            connection.apply {
                requestMethod = "GET"
                connectTimeout = 30000
                readTimeout = 30000
                setRequestProperty("Content-Type", "application/json")
            }

            val responseCode = connection.responseCode

            if (responseCode == HttpsURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val scannerResponse = json.decodeFromString<BullrunScannerResponse>(response)
                Result.success(scannerResponse)
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                Log.e(TAG, "Bullrun scanner failed: $responseCode - $error")
                Result.failure(Exception("Failed to fetch bullrun data: $responseCode"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bullrun scanner error", e)
            Result.failure(e)
        }
    }
}
