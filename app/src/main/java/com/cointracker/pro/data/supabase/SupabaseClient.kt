package com.cointracker.pro.data.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import kotlinx.serialization.json.Json

/**
 * Supabase Client Configuration
 *
 * SETUP INSTRUCTIONS:
 * 1. Go to https://supabase.com and create a new project
 * 2. Get your Project URL and anon key from Settings > API
 * 3. Replace the placeholders below with your actual values
 * 4. Run the SQL migrations in supabase/migrations/ folder
 */
object SupabaseConfig {
    const val SUPABASE_URL = "https://iyenuoujyruaotydjjqg.supabase.co"
    const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Iml5ZW51b3VqeXJ1YW90eWRqanFnIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjY3NTY0NDMsImV4cCI6MjA4MjMzMjQ0M30.5dLD50_b9ED_okjuNzUbJqdD9-AiKjgrQIBxe2SFIYk"
}

/**
 * Singleton Supabase client instance
 */
object SupabaseModule {

    // Custom JSON configuration for flexible parsing
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true  // Handle NaN, Infinity
    }

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = SupabaseConfig.SUPABASE_URL,
            supabaseKey = SupabaseConfig.SUPABASE_ANON_KEY
        ) {
            // Use custom JSON serializer
            defaultSerializer = io.github.jan.supabase.serializer.KotlinXSerializer(json)

            // Authentication
            install(Auth) {
                // Auto-refresh tokens
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
            }

            // Database access
            install(Postgrest)

            // Real-time subscriptions
            install(Realtime)
        }
    }

    // Convenience accessors
    val auth get() = client.auth
    val database get() = client.postgrest
    val realtime get() = client.realtime
}
