package com.branchdam.mobile.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.branchdam.mobile.BranchDamApplication
import com.branchdam.mobile.BuildConfig
import com.branchdam.mobile.EncryptedPrefs
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.UrlValidator
import com.branchdam.mobile.service.ImportConfirmationNotifier
import com.branchdam.mobile.service.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _serverUrl = MutableStateFlow(loadServerUrl(application))
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _apiKey = MutableStateFlow(loadApiKey(application))
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _agentId = MutableStateFlow(loadAgentId(application))
    val agentId: StateFlow<String> = _agentId.asStateFlow()

    private val _syncOnMobileData = MutableStateFlow(SyncScheduler.getSyncOnMobileData(application))
    val syncOnMobileData: StateFlow<Boolean> = _syncOnMobileData.asStateFlow()

    private val _autoImportEnabled = MutableStateFlow(ImportConfirmationNotifier.getAutoImportEnabled(application))
    val autoImportEnabled: StateFlow<Boolean> = _autoImportEnabled.asStateFlow()

    private val _namingTemplate = MutableStateFlow(EngineHolder.fetchNamingTemplate())
    val namingTemplate: StateFlow<String> = _namingTemplate.asStateFlow()

    private val _isConnected = MutableStateFlow(EngineHolder.isInitialized())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _urlError = MutableStateFlow<String?>(null)
    val urlError: StateFlow<String?> = _urlError.asStateFlow()

    fun updateServerUrl(url: String) {
        _serverUrl.value = url
        _urlError.value = validateUrl(url)
    }

    fun updateApiKey(key: String) {
        _apiKey.value = key
    }

    fun setSyncOnMobileData(enabled: Boolean) {
        _syncOnMobileData.value = enabled
        SyncScheduler.setSyncOnMobileData(getApplication(), enabled)
    }

    fun setAutoImportEnabled(enabled: Boolean) {
        _autoImportEnabled.value = enabled
        ImportConfirmationNotifier.setAutoImportEnabled(getApplication(), enabled)
    }

    fun connect() {
        val urlError = validateUrl(_serverUrl.value)
        if (urlError != null) {
            _urlError.value = urlError
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val dbFile = context.getDatabasePath("branchdam_queue.db")
            val devHosts = if (BuildConfig.DEBUG) {
                UrlValidator.DEV_CLEARTEXT_HOSTS.joinToString(",")
            } else ""
            _isConnecting.value = true
            val success = EngineHolder.initialize(
                dbPath = dbFile.absolutePath,
                baseURL = _serverUrl.value,
                apiKey = _apiKey.value,
                agentID = _agentId.value,
                devCleartextHosts = devHosts,
            )
            _isConnected.value = success
            _isConnecting.value = false
            _connectionError.value = if (success) null else "Connection failed"
            if (success) {
                val template = EngineHolder.fetchNamingTemplate()
                if (template.isNotBlank()) {
                    _namingTemplate.value = template
                }
            }
        }
    }

    private fun validateUrl(url: String): String? {
        if (url.isBlank()) return "Server URL is required"
        if (!UrlValidator.isValidServerUrl(url, BuildConfig.DEBUG)) {
            return "URL must use HTTPS" + if (BuildConfig.DEBUG) " or a local development host" else ""
        }
        return null
    }

    companion object {
        fun loadServerUrl(context: Application): String {
            val encrypted = EncryptedPrefs.get(context)
            val prefs = encrypted ?: context.getSharedPreferences(
                BranchDamApplication.PREFS_NAME,
                android.content.Context.MODE_PRIVATE,
            )
            return prefs.getString(BranchDamApplication.KEY_SERVER_URL, "") ?: ""
        }

        fun loadApiKey(context: Application): String {
            val encrypted = EncryptedPrefs.get(context)
            val prefs = encrypted ?: context.getSharedPreferences(
                BranchDamApplication.PREFS_NAME,
                android.content.Context.MODE_PRIVATE,
            )
            return prefs.getString(BranchDamApplication.KEY_API_KEY, "") ?: ""
        }

        fun loadAgentId(context: Application): String {
            val encrypted = EncryptedPrefs.get(context)
            val prefs = encrypted ?: context.getSharedPreferences(
                BranchDamApplication.PREFS_NAME,
                android.content.Context.MODE_PRIVATE,
            )
            val defaultAgentId = BranchDamApplication.DEFAULT_AGENT_ID_PREFIX + android.os.Build.MODEL
            return prefs.getString(BranchDamApplication.KEY_AGENT_ID, defaultAgentId) ?: defaultAgentId
        }
    }
}
