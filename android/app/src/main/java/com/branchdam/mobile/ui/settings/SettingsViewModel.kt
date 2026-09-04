package com.branchdam.mobile.ui.settings

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.branchdam.mobile.BranchDamApplication
import com.branchdam.mobile.BuildConfig
import com.branchdam.mobile.EncryptedPrefs
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.UrlValidator
import com.branchdam.mobile.defaultEngineDbPath
import com.branchdam.mobile.service.ImportConfirmationNotifier
import com.branchdam.mobile.service.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

typealias EngineInit = (
    dbPath: String,
    baseURL: String,
    apiKey: String,
    agentID: String,
    version: String,
    devCleartextHosts: String,
) -> Boolean

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences = resolvePrefs(application)

    private val initialConfig = BranchDamApplication.readEngineConfig(prefs)

    private val _serverUrl = MutableStateFlow(initialConfig.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _apiKey = MutableStateFlow(initialConfig.apiKey)
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _agentId = MutableStateFlow(initialConfig.agentId)
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
        _urlError.value = validateUrl(url, BuildConfig.DEBUG)
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
        val urlError = validateUrl(_serverUrl.value, BuildConfig.DEBUG)
        if (urlError != null) {
            _urlError.value = urlError
            return
        }
        _isConnecting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            persistSettings()
            val context = getApplication<Application>()
            val dbPath = defaultEngineDbPath(context.filesDir)
            val devHosts = UrlValidator.cleartextHostsCsv(BuildConfig.DEBUG)
            val success = engineInit(
                dbPath = dbPath,
                baseURL = _serverUrl.value,
                apiKey = _apiKey.value,
                agentID = _agentId.value,
                version = "0.1.0",
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

    private fun persistSettings() {
        prefs.edit()
            .putString(BranchDamApplication.KEY_SERVER_URL, _serverUrl.value)
            .putString(BranchDamApplication.KEY_API_KEY, _apiKey.value)
            .putString(BranchDamApplication.KEY_AGENT_ID, _agentId.value)
            .apply()
    }

    companion object {
        /**
         * Test seam: defaults to the production [EngineHolder.initialize]
         * call. Tests pass a lambda to drive success/failure paths
         * without instantiating a real engine.
         */
        @androidx.annotation.VisibleForTesting
        var engineInit: EngineInit = ::EngineHolder.initialize

        /**
         * Pure URL validation. Extracted so unit tests can exercise
         * the rules without instantiating an AndroidViewModel.
         */
        fun validateUrl(url: String, isDebug: Boolean): String? {
            if (url.isBlank()) return "Server URL is required"
            if (!UrlValidator.isValidServerUrl(url, isDebug)) {
                return "URL must use HTTPS" + if (isDebug) " or a local development host" else ""
            }
            return null
        }

        private fun resolvePrefs(application: Application): SharedPreferences =
            EncryptedPrefs.get(application)
                ?: application.getSharedPreferences(
                    BranchDamApplication.PREFS_NAME,
                    Context.MODE_PRIVATE,
                )
    }
}
