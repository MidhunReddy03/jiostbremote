package com.stb.mastercontrol.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stb.mastercontrol.data.AdbEngine
import com.stb.mastercontrol.data.RamStats
import com.stb.mastercontrol.data.TvApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

enum class ConnectionStatus { CONNECTING, CONNECTED, SLEEP_OR_OFFLINE }

data class UiState(
    val hostIp: String = "192.168.29.111",
    val port: Int = 5555,
    val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTING,
    val ramStats: RamStats = RamStats(freeMb = 770, totalMb = 1024),
    val installedApps: List<TvApp> = emptyList(),
    val sideloadProgress: Float? = null,
    val statusMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val adb = AdbEngine(application.applicationContext)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Trackpad delta accumulators flushed at 20Hz (every 50ms) to avoid flooding the 32-bit STB
    private val pendingDx = AtomicInteger(0)
    private val pendingDy = AtomicInteger(0)

    init {
        startConnectionWatchdog()
        startTrackpadFlusher()
    }

    private fun startConnectionWatchdog() {
        viewModelScope.launch {
            while (isActive) {
                val current = _uiState.value
                if (current.connectionStatus != ConnectionStatus.CONNECTED) {
                    _uiState.update { it.copy(connectionStatus = ConnectionStatus.CONNECTING) }
                    val ok = adb.connect(current.hostIp, current.port)
                    if (ok) {
                        _uiState.update { it.copy(connectionStatus = ConnectionStatus.CONNECTED) }
                        refreshDashboardData()
                    } else {
                        _uiState.update { it.copy(connectionStatus = ConnectionStatus.SLEEP_OR_OFFLINE) }
                    }
                } else {
                    val alive = adb.ping()
                    if (!alive) {
                        _uiState.update { it.copy(connectionStatus = ConnectionStatus.SLEEP_OR_OFFLINE) }
                    } else {
                        val ram = adb.fetchRamStats()
                        if (ram != null) {
                            _uiState.update { it.copy(ramStats = ram) }
                        }
                    }
                }
                delay(5000L)
            }
        }
    }

    private fun startTrackpadFlusher() {
        viewModelScope.launch {
            while (isActive) {
                val dx = pendingDx.getAndSet(0)
                val dy = pendingDy.getAndSet(0)
                if ((dx != 0 || dy != 0) && _uiState.value.connectionStatus == ConnectionStatus.CONNECTED) {
                    adb.sendTrackpadRoll(dx, dy)
                }
                delay(50L) // 20Hz smooth update tick
            }
        }
    }

    fun manualReconnect() {
        viewModelScope.launch {
            _uiState.update { it.copy(connectionStatus = ConnectionStatus.CONNECTING) }
            val ok = adb.connect(_uiState.value.hostIp, _uiState.value.port)
            _uiState.update {
                it.copy(
                    connectionStatus = if (ok) ConnectionStatus.CONNECTED else ConnectionStatus.SLEEP_OR_OFFLINE
                )
            }
            if (ok) refreshDashboardData()
        }
    }

    fun sendKey(keyCode: Int) {
        viewModelScope.launch {
            val ok = adb.sendKeyEvent(keyCode)
            if (!ok) {
                _uiState.update { it.copy(connectionStatus = ConnectionStatus.SLEEP_OR_OFFLINE) }
            }
        }
    }

    fun accumulateTrackpadDelta(dx: Float, dy: Float) {
        // Scale down raw pixel drag to sensible ADB roll units
        pendingDx.addAndGet((dx * 0.35f).roundToInt())
        pendingDy.addAndGet((dy * 0.35f).roundToInt())
    }

    fun pushSmartText(text: String) {
        if (text.isEmpty()) return
        viewModelScope.launch {
            adb.sendText(text)
        }
    }

    fun refreshDashboardData() {
        viewModelScope.launch {
            val ram = adb.fetchRamStats()
            val apps = adb.fetchInstalledApps()
            _uiState.update {
                it.copy(
                    ramStats = ram ?: it.ramStats,
                    installedApps = apps.ifEmpty { it.installedApps }
                )
            }
        }
    }

    fun cleanSystemCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = "Trimming caches...") }
            val ok = adb.cleanSystemCache()
            val ram = adb.fetchRamStats()
            _uiState.update {
                it.copy(
                    ramStats = ram ?: it.ramStats,
                    statusMessage = if (ok) "Freed system cache!" else "Cache trim failed"
                )
            }
        }
    }

    fun launchTvApp(packageName: String) {
        viewModelScope.launch {
            adb.launchApp(packageName)
        }
    }

    fun sideloadApk(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(sideloadProgress = 0.01f, statusMessage = "Preparing APK...") }
            val result = adb.sideloadApk(uri) { progress, msg ->
                _uiState.update { it.copy(sideloadProgress = progress, statusMessage = msg) }
            }
            result.onSuccess {
                refreshDashboardData()
                delay(2500L)
                _uiState.update { it.copy(sideloadProgress = null, statusMessage = null) }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        sideloadProgress = null,
                        statusMessage = "Error: ${err.message ?: "Install failed"}"
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        adb.close()
    }
}