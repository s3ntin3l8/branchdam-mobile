package com.branchdam.mobile

import android.app.Application
import android.content.Context
import com.branchdam.mobile.observer.MediaStoreObserver
import com.branchdam.mobile.service.SyncScheduler
import java.io.File

class BranchDamApplication : Application() {

    lateinit var mediaStoreObserver: MediaStoreObserver
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

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
        val config = readEngineConfig(this)
        EngineHolder.initialize(
            dbPath = dbPath,
            baseURL = config.serverUrl,
            apiKey = config.apiKey,
            agentID = config.agentId,
            version = "0.1.0",
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
         * Reads the engine configuration from SharedPreferences. The
         * default server URL is the Android emulator's loopback
         * (10.0.2.2 maps to the host machine's localhost). The
         * default agent ID is "pixel-fold-" + Build.MODEL.
         *
         * Extracted from the Application's initCoreEngine so unit
         * tests can verify the config-reading logic without an
         * Application context.
         */
        fun readEngineConfig(context: Context): EngineConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
