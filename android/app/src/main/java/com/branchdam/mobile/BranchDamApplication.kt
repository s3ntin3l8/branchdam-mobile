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
        val prefs = getSharedPreferences("branchdam_prefs", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", "http://10.0.2.2:8080") ?: "http://10.0.2.2:8080"
        val apiKey = prefs.getString("api_key", "") ?: ""
        val agentId = prefs.getString("agent_id", "pixel-fold-${android.os.Build.MODEL}") ?: "pixel-fold"

        EngineHolder.initialize(
            dbPath = dbPath,
            baseURL = serverUrl,
            apiKey = apiKey,
            agentID = agentId,
            version = "0.1.0",
        )
    }

    companion object {
        lateinit var instance: BranchDamApplication
            private set
    }
}
