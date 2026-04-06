package com.omnix.agent.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for API keys using EncryptedSharedPreferences (AES-256-GCM).
 * Used for Zerodha Kite Connect credentials.
 */
object EncryptedPrefsManager {

    const val PREF_KEY_ZERODHA_API_KEY = "zerodha_api_key"
    const val PREF_KEY_ZERODHA_ACCESS_TOKEN = "zerodha_access_token"
    private const val PREFS_FILE = "omnix_secure_prefs"

    private fun getPrefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun put(context: Context, key: String, value: String) {
        getPrefs(context).edit().putString(key, value).apply()
    }

    fun get(context: Context, key: String): String? =
        getPrefs(context).getString(key, null)

    fun remove(context: Context, key: String) {
        getPrefs(context).edit().remove(key).apply()
    }

    fun hasZerodhaKey(context: Context): Boolean =
        get(context, PREF_KEY_ZERODHA_API_KEY) != null

    fun getZerodhaApiKey(context: Context): String? =
        get(context, PREF_KEY_ZERODHA_API_KEY)

    fun getZerodhaAccessToken(context: Context): String? =
        get(context, PREF_KEY_ZERODHA_ACCESS_TOKEN)

    fun saveZerodhaCredentials(context: Context, apiKey: String, accessToken: String) {
        put(context, PREF_KEY_ZERODHA_API_KEY, apiKey)
        put(context, PREF_KEY_ZERODHA_ACCESS_TOKEN, accessToken)
    }
}
