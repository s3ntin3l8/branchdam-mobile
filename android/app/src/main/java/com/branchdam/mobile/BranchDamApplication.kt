package com.branchdam.mobile

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.branchdam.mobile.observer.MediaStoreObserver
import com.branchdam.mobile.service.ImportConfirmationNotifier
import com.branchdam.mobile.service.SyncNotificationHelper
import com.branchdam.mobile.service.SyncScheduler
import com.branchdam.mobile.ui.components.RawPreviewFetcher
import java.io.File

open class BranchDamApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(RawPreviewFetcher.Factory(this@BranchDamApplication))
            }
            .crossfade(true)
            .build()
    }

    lateinit var mediaStoreObserver: MediaStoreObserver
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // T2-10: copy any pre-T2-10 preference keys (sync_on_mobile_data,
        // auto_import_camera_roll) into the canonical branchdam_-prefixed
        // keys before any other component reads them. Also migrates
        // T2-5 secrets into EncryptedSharedPreferences. The migration is
        // silent and idempotent — see PrefKeyMigration.
        val nonSecretPrefs = getSharedPreferences(BranchDamKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val securePrefs = EncryptedPrefs.get(this)
        PrefKeyMigration.migrate(nonSecretPrefs, securePrefs)

        // Initialize the gomobile-bound Go engine off the main thread
        // to avoid startup lag. The holder fallbacks to mock values when
        // the AAR is not on the classpath. Subsequent EngineHolder calls
        // (like those from MediaStoreObserver) will safely queue up behind
        // this on the EngineHolder's single-threaded executor.
        val dbFile = File(filesDir, "branchdam_queue.db")
        Thread({ initCoreEngine(dbFile.absolutePath) }, "EngineInit").apply {
            isDaemon = true
            start()
        }

        mediaStoreObserver = MediaStoreObserver(this)
        mediaStoreObserver.register()

        SyncNotificationHelper.ensureChannel(this)
        ImportConfirmationNotifier.createNotificationChannel(this)

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
            version = BuildConfig.VERSION_NAME,
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
            if (encrypted == null) {
                android.util.Log.w("BranchDamApplication", "EncryptedPrefs unavailable — falling back to plain SharedPreferences (API key authentication unavailable)")
            }
            val nonSecret = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val serverUrl = encrypted?.getString(KEY_SERVER_URL, null)
                ?: nonSecret.getString(KEY_SERVER_URL, null)
                ?: DEFAULT_SERVER_URL

            // API key must only come from master-key encrypted prefs
            val apiKey = encrypted?.getString(KEY_API_KEY, null) ?: ""

            val defaultAgentId = DEFAULT_AGENT_ID_PREFIX + android.os.Build.MODEL
            val agentId = encrypted?.getString(KEY_AGENT_ID, null)
                ?: nonSecret.getString(KEY_AGENT_ID, defaultAgentId)
                ?: defaultAgentId

            return EngineConfig(serverUrl, apiKey, agentId)
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
