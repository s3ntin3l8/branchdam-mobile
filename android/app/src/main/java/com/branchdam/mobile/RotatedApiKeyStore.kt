package com.branchdam.mobile

import android.content.SharedPreferences

/**
 * Stores an API key the Go engine received from the server's key rotation
 * (branchDAM S13; see `core/client/keyrotation.go`). The engine calls
 * [store] through [EngineKeyStore] before it starts using a rotated key,
 * and again with the previous key if the server refuses the new one on
 * first use.
 *
 * The key goes only to the Keystore-backed [EncryptedPrefs] file, under
 * the same `api_key` entry [BranchDamApplication.readSecureEngineConfig]
 * reads at the next launch. [store] returns only once the key is written
 * (`commit`, then read back) and throws otherwise, so the engine keeps
 * using the key it has: a key that was used but not stored would be lost
 * at the next launch, after the server had already revoked the old one.
 * There is no plaintext fallback when encrypted storage is unavailable.
 *
 * [securePrefs] returns the encrypted prefs, or null when the Keystore
 * failed. Blocking: the engine calls it on a Go thread.
 */
class RotatedApiKeyStore(private val securePrefs: () -> SharedPreferences?) {

    fun store(apiKey: String) {
        require(apiKey.isNotEmpty()) { "refusing to store an empty API key" }
        val prefs = securePrefs() ?: throw IllegalStateException(KEY_STORAGE_UNAVAILABLE)
        val editor = prefs.edit()
        editor.putString(BranchDamApplication.KEY_API_KEY, apiKey)
        val written = editor.commit()
        if (!written || prefs.getString(BranchDamApplication.KEY_API_KEY, null) != apiKey) {
            throw IllegalStateException(WRITE_FAILED)
        }
    }

    companion object {
        const val KEY_STORAGE_UNAVAILABLE =
            "Secure key storage (Android Keystore) is unavailable, so the rotated API key was not stored"
        const val WRITE_FAILED = "Could not write the rotated API key to secure storage"
    }
}
