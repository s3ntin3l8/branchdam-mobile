package com.branchdam.mobile

import android.content.SharedPreferences

/**
 * Writes the connection settings (server URL, API key, agent ID).
 *
 * The API key only ever goes to the Keystore-backed
 * [EncryptedPrefs] file. When that file can't be opened (a broken or
 * missing Android Keystore), the key is NOT written anywhere: there is no
 * plaintext fallback. The server URL and agent ID are not secrets and are
 * always written to the plain prefs too, so the rest of the app can still
 * read them. Every save also deletes an `api_key` entry from the plain
 * prefs, which older releases could leave there.
 */
object ConnectionSettingsStore {

    enum class SaveResult {
        /** Everything was written; the API key (if any) is in encrypted storage. */
        SAVED,

        /** Encrypted storage is unavailable, so the non-empty API key was not saved. */
        KEY_STORAGE_UNAVAILABLE,

        /** Encrypted storage refused the write. */
        WRITE_FAILED,
    }

    /** Shown when the Keystore is unavailable and an API key would have to be stored. */
    const val KEY_STORAGE_UNAVAILABLE_MESSAGE =
        "Secure key storage (Android Keystore) is unavailable on this device, " +
            "so the API key can't be saved. branchDAM never stores it unencrypted."

    const val WRITE_FAILED_MESSAGE = "Could not save the API key to secure storage. Try again."

    /**
     * Saves the settings. [secure] is the encrypted prefs, or null when the
     * Keystore failed; [plain] is the ordinary prefs file. Blocking
     * (`commit`): call it off the main thread.
     */
    fun save(
        secure: SharedPreferences?,
        plain: SharedPreferences,
        serverUrl: String,
        apiKey: String,
        agentId: String,
    ): SaveResult {
        plain.edit()
            .putString(BranchDamApplication.KEY_SERVER_URL, serverUrl)
            .putString(BranchDamApplication.KEY_AGENT_ID, agentId)
            .remove(BranchDamApplication.KEY_API_KEY)
            .commit()

        if (secure == null) {
            return if (apiKey.isEmpty()) SaveResult.SAVED else SaveResult.KEY_STORAGE_UNAVAILABLE
        }
        val ok = secure.edit()
            .putString(BranchDamApplication.KEY_SERVER_URL, serverUrl)
            .putString(BranchDamApplication.KEY_API_KEY, apiKey)
            .putString(BranchDamApplication.KEY_AGENT_ID, agentId)
            .commit()
        return if (ok) SaveResult.SAVED else SaveResult.WRITE_FAILED
    }

    /** The user-facing message for a failed [save], or null for [SaveResult.SAVED]. */
    fun messageFor(result: SaveResult): String? = when (result) {
        SaveResult.SAVED -> null
        SaveResult.KEY_STORAGE_UNAVAILABLE -> KEY_STORAGE_UNAVAILABLE_MESSAGE
        SaveResult.WRITE_FAILED -> WRITE_FAILED_MESSAGE
    }
}
