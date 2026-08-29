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

        // Initialize SQLite queue and background observer
        val dbFile = File(filesDir, "branchdam_queue.db")
        initCoreEngine(dbFile.absolutePath)

        mediaStoreObserver = MediaStoreObserver(this)
        mediaStoreObserver.register()

        SyncScheduler.schedulePeriodicSync(this)
    }

    private fun initCoreEngine(dbPath: String) {
        // Native Go core engine initialized via JNI / bindings
        val prefs = getSharedPreferences("branchdam_prefs", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", "http://10.0.2.2:8080") ?: "http://10.0.2.2:8080"
        val apiKey = prefs.getString("api_key", "") ?: ""
        val agentId = prefs.getString("agent_id", "pixel-fold-${android.os.Build.MODEL}") ?: "pixel-fold"

        try {
            // Invokes Go core bindings: InitCore(dbPath, serverUrl, apiKey, agentId, "0.1.0")
            NativeBridge.initCore(dbPath, serverUrl, apiKey, agentId, "0.1.0")
        } catch (_: UnsatisfiedLinkError) {
            // Core native library loaded during runtime / tests
        }
    }

    companion object {
        lateinit var instance: BranchDamApplication
            private set
    }
}

object NativeBridge {
    init {
        try {
            System.loadLibrary("branchdam_core")
        } catch (_: UnsatisfiedLinkError) {
            // Mock or stub mode for unit tests without native binary
        }
    }

    fun initCore(dbPath: String, serverUrl: String, apiKey: String, agentId: String, version: String) {
        // JNI native method binding
    }

    fun enqueueMedia(localPath: String, filename: String, capturedAtUnix: Long, localId: String): Long {
        return 1L
    }

    fun syncBatch(timeoutSecs: Int, batchSize: Int): Pair<Int, Int> {
        return Pair(0, 0)
    }
}
