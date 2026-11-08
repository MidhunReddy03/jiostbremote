package com.stb.mastercontrol.ui

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.view.Surface
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
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ConnectionStatus { CONNECTING, CONNECTED, SLEEP_OR_OFFLINE }

/** Live state of the gyro-driven air mouse. */
data class AirMouseState(
    /** `true` while the phone's sensors are streaming cursor deltas to the STB. */
    val armed: Boolean = false,
    /** Normalised pointer seat for the on-phone visual, clamped to -1..1. */
    val pointerX: Float = 0f,
    val pointerY: Float = 0f,
    /** Multiplier applied to gyro deltas, 0.5 .. 3.0. */
    val sensitivity: Float = 1f
)

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

    // ---------------------------------------------------------------------
    // Dark mode
    // ---------------------------------------------------------------------
    private val _isDarkMode = MutableStateFlow(readDarkModePref(application))
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun toggleDarkMode() {
        val next = !_isDarkMode.value
        _isDarkMode.value = next
        runCatching {
            getApplication<Application>()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DARK_MODE, next)
                .apply()
        }
    }

    // ---------------------------------------------------------------------
    // Air mouse (phone gyro -> TV cursor)
    // ---------------------------------------------------------------------
    private val _airMouseState = MutableStateFlow(AirMouseState())
    val airMouseState: StateFlow<AirMouseState> = _airMouseState.asStateFlow()

    /** Gyro deltas accumulated as milli-units ("milli-roll steps"). */
    private val pendingAirX = AtomicInteger(0)
    private val pendingAirY = AtomicInteger(0)
    /** Unused remainder from the last milli-unit flush, kept to avoid drift. */
    private val leftoverX = AtomicInteger(0)
    private val leftoverY = AtomicInteger(0)
    private val sensorLock = Any()
    private var lastSensorNanos = 0L
    private var sensorListener: SensorEventListener? = null
    @Volatile private var lastAirMouseActivity = 0L

    // Trackpad delta accumulators flushed at 20Hz (every 50ms) to avoid flooding the 32-bit STB
    private val pendingDx = AtomicInteger(0)
    private val pendingDy = AtomicInteger(0)

    private var flusherStarted = false

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

    private fun startAirMouseFlusher() {
        viewModelScope.launch {
            while (isActive) {
                val armed = _airMouseState.value.armed
                if (armed) {
                    // Drain gyro deltas, drop sub-unit remainders into the next tick
                    val rawX = pendingAirX.getAndSet(0)
                    val rawY = pendingAirY.getAndSet(0)
                    val combinedX = rawX + leftoverX.getAndSet(0)
                    val combinedY = rawY + leftoverY.getAndSet(0)
                    val dx = combinedX / MILLI_SCALE
                    val dy = combinedY / MILLI_SCALE
                    leftoverX.set(combinedX - dx * MILLI_SCALE)
                    leftoverY.set(combinedY - dy * MILLI_SCALE)
                    if (dx != 0 || dy != 0) {
                        lastAirMouseActivity = System.currentTimeMillis()
                        adb.sendMouseRoll(dx, dy)
                    }
                    // Keep the cursor rendered on the TV: many builds hide it
                    // after a few idle seconds unless a mouse event arrives.
                    val idleFor = System.currentTimeMillis() - lastAirMouseActivity
                    if (idleFor > CURSOR_KEEPALIVE_MS) {
                        adb.wakePointer()
                        lastAirMouseActivity = System.currentTimeMillis()
                    }
                }
                delay(40L) // ~25Hz, smooth enough for a gyro pointer
            }
        }
    }

    // ---------------------------------------------------------------------
    // Air mouse controls
    // ---------------------------------------------------------------------
    fun toggleAirMouse() {
        if (_airMouseState.value.armed) disarmAirMouse() else armAirMouse()
    }

    private fun armAirMouse() {
        if (_airMouseState.value.armed) return
        val sm = getApplication<Application>()
            .getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (sm == null || gyro == null) {
            _uiState.update {
                it.copy(statusMessage = "Air mouse unavailable on this phone")
            }
            return
        }
        if (sensorListener == null) {
            val listener = object : SensorEventListener {
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                override fun onSensorChanged(event: SensorEvent) {
                    handleGyroEvent(event)
                }
            }
            sensorListener = listener
            sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        }
        if (!flusherStarted) {
            flusherStarted = true
            startAirMouseFlusher()
        }
        _airMouseState.update { it.copy(armed = true) }
        lastAirMouseActivity = System.currentTimeMillis()
        // First micro-move wakes the on-screen pointer arrow so the user can see it.
        viewModelScope.launch {
            runCatching { adb.wakePointer() }
        }
    }

    private fun disarmAirMouse() {
        _airMouseState.update { it.copy(armed = false, pointerX = 0f, pointerY = 0f) }
        val listener = sensorListener
        if (listener != null) {
            runCatching {
                val sm = getApplication<Application>()
                    .getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                sm?.unregisterListener(listener)
            }
            sensorListener = null
        }
    }

    /**
     * Integrates the rotation rate into a relative cursor delta.
     *
     * Portrait / axis map (phone held in front of the TV):
     *   yaw  (X of the cursor)   -> device GYROSCOPE Y axis
     *   pitch(Y of the cursor)   -> device GYROSCOPE X axis (inverted)
     *
     * Landscape is remapped based on the display rotation.
     */
    private fun handleGyroEvent(event: SensorEvent) {
        if (!_airMouseState.value.armed) return
        val dtSeconds: Float
        synchronized(sensorLock) {
            if (lastSensorNanos == 0L) {
                lastSensorNanos = event.timestamp
                return
            }
            dtSeconds = (event.timestamp - lastSensorNanos) * NANOS_TO_SECONDS
            lastSensorNanos = event.timestamp
        }
        if (dtSeconds <= 0f || dtSeconds > 0.5f) return // drop large gaps (device wake / lag)

        val rotation = event.values
        if (rotation.size < 3) return
        val rX = rotation[0]
        val rY = rotation[1]
        val display = (getApplication<Application>()
            .getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
        val (yaw, pitch) = when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> Pair(-rX, -rY)
            Surface.ROTATION_180 -> Pair(rX, rY)
            Surface.ROTATION_270 -> Pair(rX, rY)
            else -> Pair(rY, -rX)
        }
        val sensitivity = _airMouseState.value.sensitivity
        val milli = STEPS_PER_RADIAN * MILLI_SCALE * sensitivity
        pendingAirX.addAndGet((yaw * dtSeconds * milli).roundToInt())
        pendingAirY.addAndGet((pitch * dtSeconds * milli).roundToInt())

        // Mirror the movement on the on-phone visual pointer (wraps like a ball).
        _airMouseState.update { state ->
            state.copy(
                pointerX = wrapped(state.pointerX + yaw * dtSeconds * sensitivity * TRAIL_SCALE),
                pointerY = wrapped(state.pointerY + pitch * dtSeconds * sensitivity * TRAIL_SCALE)
            )
        }
    }

    fun setAirMouseSensitivity(value: Float) {
        _airMouseState.update { it.copy(sensitivity = value.coerceIn(0.5f, 3f)) }
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
        disarmAirMouse()
        adb.close()
    }

    companion object {
        private const val PREFS_NAME = "stb_prefs"
        private const val KEY_DARK_MODE = "dark_mode"

        /** ~100 TV-cursor pixels per radian of phone yaw/pitch. */
        private const val STEPS_PER_RADIAN = 100f
        private const val MILLI_SCALE = 1000
        /** How far the on-phone visual pointer travels per rad/s of sustained tilt. */
        private const val TRAIL_SCALE = 1.4f
        private const val NANOS_TO_SECONDS = 1f / 1_000_000_000f
        private const val CURSOR_KEEPALIVE_MS = 3000L

        private fun readDarkModePref(app: Application): Boolean = runCatching {
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DARK_MODE, false)
        }.getOrDefault(false)

        /** Wraps a normalised -1..1 pointer value so the on-phone ball never sticks. */
        private fun wrapped(value: Float): Float = when {
            value > 1f -> value - 2f
            value < -1f -> value + 2f
            else -> value
        }
    }
}