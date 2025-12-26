package com.cointracker.pro.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Fear & Greed Index API from alternative.me
 * Free public API - no auth required
 */
interface FearGreedApiService {
    @GET("fng/")
    suspend fun getFearGreedIndex(
        @Query("limit") limit: Int = 1,
        @Query("format") format: String = "json"
    ): FearGreedResponse
}

data class FearGreedResponse(
    @SerializedName("name") val name: String,
    @SerializedName("data") val data: List<FearGreedData>,
    @SerializedName("metadata") val metadata: FearGreedMetadata?
)

data class FearGreedData(
    @SerializedName("value") val value: String,
    @SerializedName("value_classification") val classification: String,
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("time_until_update") val timeUntilUpdate: String?
)

data class FearGreedMetadata(
    @SerializedName("error") val error: String?
)

object FearGreedApi {
    private const val BASE_URL = "https://api.alternative.me/"

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val service: FearGreedApiService = retrofit.create(FearGreedApiService::class.java)
}
