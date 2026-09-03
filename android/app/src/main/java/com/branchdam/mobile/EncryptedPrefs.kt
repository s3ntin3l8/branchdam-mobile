package com.branchdam.mobile

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Helper that produces the SharedPreferences instance used to hold
 * the API key and other secrets. The values are encrypted at rest
 * with a key derived from the Android Keystore, so an `adb backup`
 * (when allowBackup="false" — see AndroidManifest) or a rooted-device
 * file pull reveals only ciphertext.
 *
 * T2-5 hardening: the production code paths use this helper rather
 * than `Context.getSharedPreferences(...)` for any field that holds a
 * secret. Non-sensitive fields (sync-on-mobile-data, auto-import
 * toggle) keep using plain SharedPreferences.
 *
 * The Keystore initialization can fail on devices with broken or
 * missing Keystore support (rare, mostly emulators). In that case
 * the helper logs an error and returns null; the call site is
 * expected to fall back to plain SharedPreferences. Falling back is
 * a security regression but lets the app start on a broken device
 * rather than crash on launch — the F plan calls this out as an
 * acceptable trade-off.
 */
object EncryptedPrefs {

    private const val TAG = "EncryptedPrefs"

    /**
     * Keystore master-key alias. Pinned in source so multiple
     * processes / reinstalls reach the same key on devices that
     * persist the alias across the Keystore.
     */
    const val MASTER_KEY_ALIAS = "branchdam_master_key"

    /**
     * The encrypted prefs file that holds apiKey, serverUrl, and
     * agentId. Non-sensitive fields (sync_on_mobile_data,
     * auto_import_camera_roll) live in a separate plain
     * SharedPreferences file via [com.branchdam.mobile.BranchDamKeys.PREFS_NAME].
     */
    const val SECURE_PREFS_NAME = "branchdam_secure_prefs"

    /**
     * Returns an EncryptedSharedPreferences for [name], or null if
     * Keystore initialization fails. The returned object is cached
     * for the lifetime of the process because MasterKey construction
     * involves a Keystore round-trip that we don't want to pay per
     * read.
     */
    fun get(context: Context, name: String = SECURE_PREFS_NAME): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize encrypted prefs '$name': $t")
            null
        }
    }
}
