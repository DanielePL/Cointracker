package com.cointracker.pro.data.binance

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Helper for creating Binance API signatures
 * Uses HMAC-SHA256 as required by Binance
 */
object BinanceSignature {

    /**
     * Create HMAC-SHA256 signature for Binance API
     * @param data The query string to sign (e.g., "timestamp=1234567890")
     * @param secret The API secret key
     * @return Hex-encoded signature
     */
    fun sign(data: String, secret: String): String {
        val hmacSha256 = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        hmacSha256.init(secretKey)
        val hash = hmacSha256.doFinal(data.toByteArray(Charsets.UTF_8))
        return hash.toHexString()
    }

    /**
     * Create signature for account endpoint
     * @param timestamp Current timestamp in milliseconds
     * @param secret The API secret key
     * @param additionalParams Any additional query parameters
     * @return Hex-encoded signature
     */
    fun signAccountRequest(
        timestamp: Long,
        secret: String,
        additionalParams: Map<String, String> = emptyMap()
    ): String {
        val params = buildString {
            additionalParams.forEach { (key, value) ->
                if (isNotEmpty()) append("&")
                append("$key=$value")
            }
            if (isNotEmpty()) append("&")
            append("timestamp=$timestamp")
        }
        return sign(params, secret)
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
