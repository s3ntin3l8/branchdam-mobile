package com.branchdam.mobile

import android.content.Context
import android.util.Log
import io.branchdam.core.branchdam.Branchdam
import io.branchdam.core.branchdam.KeyStore

/**
 * Gives the Go engine a [KeyStore] backed by [RotatedApiKeyStore], so it
 * can adopt a rotated API key the server delivers on a handshake. The Go
 * side holds it for the whole process, across every engine
 * [EngineHolder.initialize] opens, so it is installed once at startup,
 * before the first engine opens.
 */
object EngineKeyStore {
    private const val TAG = "EngineKeyStore"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val store = RotatedApiKeyStore { EncryptedPrefs.get(appContext) }
        try {
            Branchdam.bindingSetKeyStore(object : KeyStore {
                // Called on a Go thread. A thrown exception reaches the
                // engine as an error, and it then stays on its current key.
                override fun storeAPIKey(apiKey: String?) {
                    store.store(apiKey ?: "")
                }
            })
        } catch (t: Throwable) {
            // No native library (unit tests without the AAR): there is no
            // engine to give it to.
            Log.w(TAG, "bindingSetKeyStore failed: $t")
        }
    }
}
