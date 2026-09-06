package com.branchdam.mobile.ui.settings

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.branchdam.mobile.BranchDamApplication
import com.branchdam.mobile.BranchDamKeys
import com.branchdam.mobile.BuildConfig
import com.branchdam.mobile.EncryptedPrefs
import com.branchdam.mobile.EngineHolder
import com.branchdam.mobile.UrlValidator
import com.branchdam.mobile.defaultEngineDbPath
import com.branchdam.mobile.service.ImportConfirmationNotifier
import com.branchdam.mobile.service.SyncScheduler
import com.branchdam.mobile.ui.PairingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

typealias EngineInit = (
    dbPath: String,
    baseURL: String,
    apiKey: String,
    agentID: String,
    version: String,
    devCleartextHosts: String,
) -> Boolean

/**
 * Test seam for [SettingsViewModel.checkConnection] — the production
 * lambda delegates to [EngineHolder.testConnection] which runs on the
 * gomobile binding. Tests pass a pure (suspending) lambda to drive
 * success / failure / hang paths without loading the AAR. Marked
 * `suspend` so the timeout test can use `delay`, which cooperates
 * with the test scheduler's virtual clock.
 */
typealias TestConnectionFn = suspend () -> Boolean

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences = resolvePrefs(application)
    private val nonSecretPrefs: SharedPreferences = application.getSharedPreferences(BranchDamKeys.PREFS_NAME, Context.MODE_PRIVATE)

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

    // Sync settings
    private val _syncIntervalMinutes = MutableStateFlow(nonSecretPrefs.getInt(BranchDamKeys.SYNC_INTERVAL_MINUTES, BranchDamKeys.DEFAULT_SYNC_INTERVAL_MINUTES))
    val syncIntervalMinutes: StateFlow<Int> = _syncIntervalMinutes.asStateFlow()

    private val _syncOnBatteryOnly = MutableStateFlow(nonSecretPrefs.getBoolean(BranchDamKeys.SYNC_ON_BATTERY_ONLY, false))
    val syncOnBatteryOnly: StateFlow<Boolean> = _syncOnBatteryOnly.asStateFlow()

    // Advanced settings
    private val _uploadBatchSize = MutableStateFlow(nonSecretPrefs.getInt(BranchDamKeys.UPLOAD_BATCH_SIZE, BranchDamKeys.DEFAULT_UPLOAD_BATCH_SIZE))
    val uploadBatchSize: StateFlow<Int> = _uploadBatchSize.asStateFlow()

    private val _syncTimeoutSecs = MutableStateFlow(nonSecretPrefs.getInt(BranchDamKeys.SYNC_TIMEOUT_SECS, BranchDamKeys.DEFAULT_SYNC_TIMEOUT_SECS))
    val syncTimeoutSecs: StateFlow<Int> = _syncTimeoutSecs.asStateFlow()

    private val _observerDebounceMs = MutableStateFlow(nonSecretPrefs.getLong(BranchDamKeys.OBSERVER_DEBOUNCE_MS, BranchDamKeys.DEFAULT_OBSERVER_DEBOUNCE_MS))
    val observerDebounceMs: StateFlow<Long> = _observerDebounceMs.asStateFlow()

    val versionName: String = BuildConfig.VERSION_NAME
    val versionCode: Int = BuildConfig.VERSION_CODE

    // Initial value reflects the persisted engine state so the
    // Settings screen doesn't flash "Disconnected" for the few
    // hundred milliseconds between init and the first
    // checkConnection() result. The async refresh on init /
    // LaunchedEffect still updates this if the server has gone
    // away since last launch.
    private val _isConnected = MutableStateFlow(EngineHolder.isInitialized())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _urlError = MutableStateFlow<String?>(null)
    val urlError: StateFlow<String?> = _urlError.asStateFlow()

    init {
        // Cold-start refresh. The SettingsScreen also re-fires this
        // from its `LaunchedEffect(Unit)` on every re-entry, so a
        // user navigating back from the Sync Status screen sees a
        // fresh handshake result without having to tap Refresh.
        checkConnection()
    }

    /**
     * Verifies the server handshake against the current server URL
     * and updates [isConnected]. Bounds the wait via
     * [reachabilityTimeoutMs] so a misconfigured server's TCP
     * timeout (~75s) can't lock the screen UI or the
     * single-threaded [EngineHolder] executor that backs the
     * gomobile binding.
     */
    fun checkConnection() {
        viewModelScope.launch {
            val isReachable = withContext(testIoDispatcher) {
                withTimeoutOrNull(reachabilityTimeoutMs) {
                    testConnectionFn()
                } ?: false
            }
            _isConnected.value = isReachable
        }
    }

    fun updateServerUrl(url: String) {
        _serverUrl.value = url
        _urlError.value = validateUrl(url, BuildConfig.DEBUG)
    }

    fun updateApiKey(key: String) {
        _apiKey.value = key
    }

    /**
     * Apply a QR-scanned pairing config and immediately kick off the
     * connect flow. The QR parser has already validated the URL
     * shape (see [QrParser.parseQrPayload]), so the URL validation
     * gate inside [connect] is purely defensive.
     */
    fun applyPairingConfig(config: PairingConfig) {
        _serverUrl.value = config.serverUrl
        _apiKey.value = config.apiKey
        _agentId.value = config.agentId
        _urlError.value = validateUrl(config.serverUrl, BuildConfig.DEBUG)
        connect()
    }

    fun setSyncOnMobileData(enabled: Boolean) {
        _syncOnMobileData.value = enabled
        SyncScheduler.setSyncOnMobileData(getApplication(), enabled)
    }

    fun setAutoImportEnabled(enabled: Boolean) {
        _autoImportEnabled.value = enabled
        ImportConfirmationNotifier.setAutoImportEnabled(getApplication(), enabled)
    }

    fun setSyncIntervalMinutes(minutes: Int) {
        _syncIntervalMinutes.value = minutes
        nonSecretPrefs.edit().putInt(BranchDamKeys.SYNC_INTERVAL_MINUTES, minutes).apply()
        SyncScheduler.schedulePeriodicSync(getApplication())
    }

    fun setSyncOnBatteryOnly(enabled: Boolean) {
        _syncOnBatteryOnly.value = enabled
        nonSecretPrefs.edit().putBoolean(BranchDamKeys.SYNC_ON_BATTERY_ONLY, enabled).apply()
        SyncScheduler.schedulePeriodicSync(getApplication())
    }

    fun setUploadBatchSize(size: Int) {
        _uploadBatchSize.value = size
        nonSecretPrefs.edit().putInt(BranchDamKeys.UPLOAD_BATCH_SIZE, size).apply()
    }

    fun setSyncTimeoutSecs(secs: Int) {
        _syncTimeoutSecs.value = secs
        nonSecretPrefs.edit().putInt(BranchDamKeys.SYNC_TIMEOUT_SECS, secs).apply()
    }

    fun setObserverDebounceMs(ms: Long) {
        _observerDebounceMs.value = ms
        nonSecretPrefs.edit().putLong(BranchDamKeys.OBSERVER_DEBOUNCE_MS, ms).apply()
    }

    fun connect() {
        val urlError = validateUrl(_serverUrl.value, BuildConfig.DEBUG)
        if (urlError != null) {
            _urlError.value = urlError
            return
        }
        _isConnecting.value = true
        viewModelScope.launch(testIoDispatcher) {
            persistSettings()
            val context = getApplication<Application>()
            val dbPath = defaultEngineDbPath(context.filesDir)
            val devHosts = UrlValidator.cleartextHostsCsv(BuildConfig.DEBUG)
            val success = engineInit(
                dbPath,
                _serverUrl.value,
                _apiKey.value,
                _agentId.value,
                BuildConfig.VERSION_NAME,
                devHosts,
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
         * Default upper bound on the time `checkConnection` will wait
         * for the server handshake before treating it as unreachable.
         * Matches the SyncStatusViewModel timeout so the two screens
         * stay consistent — the Settings screen just runs the same
         * check on demand rather than as a continuous flow.
         */
        @androidx.annotation.VisibleForTesting
        var reachabilityTimeoutMs: Long = 5_000L

        /**
         * Test seam for [checkConnection]. Defaults to the production
         * [EngineHolder.testConnection] call. Tests pass a lambda to
         * drive success / failure / hang paths without loading the
         * gomobile AAR.
         */
        @androidx.annotation.VisibleForTesting
        var testConnectionFn: TestConnectionFn = { EngineHolder.testConnection() }

        /**
         * Test seam for the dispatcher used inside `checkConnection`.
         * Defaults to [Dispatchers.IO]; tests substitute the test
         * scheduler so `withTimeoutOrNull` advances on the virtual
         * clock.
         */
        @androidx.annotation.VisibleForTesting
        var testIoDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

        /**
         * Test seam: defaults to the production [EngineHolder.initialize]
         * call. Tests pass a lambda to drive success/failure paths
         * without instantiating a real engine.
         */
        @androidx.annotation.VisibleForTesting
        var engineInit: EngineInit = { dbPath, baseURL, apiKey, agentID, version, devCleartextHosts ->
            EngineHolder.initialize(
                dbPath = dbPath,
                baseURL = baseURL,
                apiKey = apiKey,
                agentID = agentID,
                version = version,
                devCleartextHosts = devCleartextHosts,
            )
        }

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
