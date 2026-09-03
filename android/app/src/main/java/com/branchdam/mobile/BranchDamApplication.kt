package com.branchdam.mobile

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.branchdam.mobile.observer.MediaStoreObserver
import com.branchdam.mobile.service.SyncScheduler
import java.io.File

class BranchDamApplication : Application() {

    lateinit var mediaStoreObserver: MediaStoreObserver
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // T2-10: copy any pre-T2-10 preference keys (sync_on_mobile_data,
        // auto_import_camera_roll) into the canonical branchdam_-prefixed
        // keys before any other component reads them. The migration is
        // silent and idempotent — see PrefKeyMigration.
        val nonSecretPrefs = getSharedPreferences(BranchDamKeys.PREFS_NAME, Context.MODE_PRIVATE)
        PrefKeyMigration.migrate(nonSecretPrefs)

        // Initialize the gomobile-bound Go engine. The holder falls back
        // to mock values when the AAR is not on the classpath, so
        // unit tests that run without the native binary still work.
        val dbFile = File(filesDir, "branchdam_queue.db")
        initCoreEngine(dbFile.absolutePath)

        mediaStoreObserver = MediaStoreObserver(this)
        mediaStoreObserver.register()

        SyncScheduler.schedulePeriodicSync(this)
    }

    private fun initCoreEngine(dbPath: String) {
        val config = readSecureEngineConfig(this)
        val cleartextHosts = if (BuildConfig.DEBUG) {
            UrlValidator.DEV_CLEARTEXT_HOSTS.joinToString(",")
        } else ""
        EngineHolder.initialize(
            dbPath = dbPath,
            baseURL = config.serverUrl,
            apiKey = config.apiKey,
            agentID = config.agentId,
            version = "0.1.0",
            devCleartextHosts = cleartextHosts,
        )
    }

    companion object {
        lateinit var instance: BranchDamApplication
            private set

        const val PREFS_NAME = "branchdam_prefs"
        const val KEY_SERVER_URL = "server_url"
        // pragma: allowlist secret
        const val KEY_API_KEY = "api_key" // pragma: allowlist secret
        const val KEY_AGENT_ID = "agent_id"
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080"
        const val DEFAULT_AGENT_ID_PREFIX = "pixel-fold-"

        /**
         * Reads the engine configuration from the EncryptedSharedPreferences
         * produced by [EncryptedPrefs]. Falls back to plain
         * SharedPreferences if Keystore initialization fails (the
         * EncryptedPrefs helper logs and returns null in that case) so
         * the app can still start on a broken device. The default
         * server URL is the Android emulator's loopback (10.0.2.2 maps
         * to the host machine's localhost). The default agent ID is
         * "pixel-fold-" + Build.MODEL.
         *
         * T2-5: secrets are stored in EncryptedSharedPreferences so
         * an `adb backup` does not extract them. See
         * AndroidManifest.xml's `allowBackup="false"` and
         * EncryptedPrefs.kt for the encryption story.
         */
        fun readSecureEngineConfig(context: Context): EngineConfig {
            val encrypted = EncryptedPrefs.get(context)
            val prefs = encrypted ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return readEngineConfig(prefs)
        }

        /**
         * Pure function that builds an [EngineConfig] from any
         * [SharedPreferences] instance. Extracted from the
         * Application's initCoreEngine so unit tests can verify the
         * config-reading logic without an Application context. T2-5
         * tests pass a mocked EncryptedSharedPreferences to verify
         * the secure-storage path; pre-T2-5 tests passed a plain
         * SharedPreferences.
         */
        fun readEngineConfig(prefs: SharedPreferences): EngineConfig {
            val serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
            val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
            val defaultAgentId = DEFAULT_AGENT_ID_PREFIX + android.os.Build.MODEL
            val agentId = prefs.getString(KEY_AGENT_ID, defaultAgentId) ?: defaultAgentId
            return EngineConfig(serverUrl, apiKey, agentId)
        }
    }
}

data class EngineConfig(
    val serverUrl: String,
    val apiKey: String,
    val agentId: String,
)
