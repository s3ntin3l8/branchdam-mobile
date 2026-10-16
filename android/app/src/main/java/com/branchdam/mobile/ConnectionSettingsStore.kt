package com.branchdam.mobile

import android.content.SharedPreferences
import androidx.annotation.StringRes

/**
 * Writes the connection settings (server URL, API key, agent ID).
 *
 * The API key only ever goes to the Keystore-backed
 * [EncryptedPrefs] file. When that file can't be opened (a broken or
 * missing Android Keystore), the key is NOT written anywhere: there is no
 * plaintext fallback. The server URL and agent ID are not secrets and are
 * written to the plain prefs too, so the rest of the app can still read
 * them. Every plain write also deletes an `api_key` entry from the plain
 * prefs, which older releases could leave there.
 *
 * The encrypted prefs are written first. The plain copies are written only
 * once that write has committed, so a failed save never leaves the plain
 * and encrypted `serverUrl` disagreeing in the direction that matters:
 * [BranchDamApplication.readSecureEngineConfig] prefers the encrypted copy.
 */
object ConnectionSettingsStore {

    enum class SaveResult {
        /** Everything was written; the API key (if any) is in encrypted storage. */
        SAVED,

        /** Encrypted storage is unavailable, so the non-empty API key was not saved. */
        KEY_STORAGE_UNAVAILABLE,

        /** The encrypted or the plain prefs refused the write. */
        WRITE_FAILED,
    }

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
        if (secure == null) {
            // No encrypted copy exists to diverge from. The non-secret
            // fields are still saved (and a legacy plaintext key removed)
            // even when the key itself has to be refused.
            val plainOk = writePlain(plain, serverUrl, agentId)
            return when {
                apiKey.isNotEmpty() -> SaveResult.KEY_STORAGE_UNAVAILABLE
                plainOk -> SaveResult.SAVED
                else -> SaveResult.WRITE_FAILED
            }
        }
        val secureOk = secure.edit()
            .putString(BranchDamApplication.KEY_SERVER_URL, serverUrl)
            .putString(BranchDamApplication.KEY_API_KEY, apiKey)
            .putString(BranchDamApplication.KEY_AGENT_ID, agentId)
            .commit()
        if (!secureOk) return SaveResult.WRITE_FAILED
        return if (writePlain(plain, serverUrl, agentId)) SaveResult.SAVED else SaveResult.WRITE_FAILED
    }

    /** Writes the non-secret copies and drops any plaintext key; returns the `commit()` result. */
    private fun writePlain(plain: SharedPreferences, serverUrl: String, agentId: String): Boolean =
        plain.edit()
            .putString(BranchDamApplication.KEY_SERVER_URL, serverUrl)
            .putString(BranchDamApplication.KEY_AGENT_ID, agentId)
            .remove(BranchDamApplication.KEY_API_KEY)
            .commit()

    /**
     * The string resource of the user-facing message for a failed [save],
     * or null for [SaveResult.SAVED]. [R.string.connection_key_storage_unavailable]
     * is also what the connection screen shows when the Keystore is
     * unavailable and an API key would have to be stored.
     */
    @StringRes
    fun messageResFor(result: SaveResult): Int? = when (result) {
        SaveResult.SAVED -> null
        SaveResult.KEY_STORAGE_UNAVAILABLE -> R.string.connection_key_storage_unavailable
        SaveResult.WRITE_FAILED -> R.string.connection_write_failed
    }
}
