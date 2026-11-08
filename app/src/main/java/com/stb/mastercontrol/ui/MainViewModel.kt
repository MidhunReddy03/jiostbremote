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
import com.stb.mastercontrol.data.BtMouseController
import com.stb.mastercontrol.data.BtMouseController.BtPeer
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

enum class AirCalibrationStep { NONE, POINT_AT_TV, DONE }

/** How cursor movements reach the TV. */
enum class AirMouseTransport {
    /** Real hardware cursor over Bluetooth HID (TV sees a genuine mouse). */
    BLUETOOTH,
    /** Legacy ADB `input` injection (keys, dashboard etc. still use this). */
    ADB
}

/** Live state of the gyro-driven air mouse. */
data class AirMouseState(
    /** `true` while the phone's sensors are streaming cursor deltas to the STB. */
    val armed: Boolean = false,
    /** Normalised pointer seat for the on-phone visual, clamped to -1..1. */
    val pointerX: Float = 0f,
    val pointerY: Float = 0f,
    /** Multiplier applied to gyro deltas, 0.5 .. 3.0. */
    val sensitivity: Float = 1f,
    /** Whether a one-time rest-position calibration has been stored. */
    val calibrated: Boolean = false,
    /** Current step of the one-time calibration wizard. */
    val calStep: AirCalibrationStep = AirCalibrationStep.NONE,
    /** True while the on-screen cursor overlay is being drawn on the TV. */
    val showPointer: Boolean = true,
    /** Selected transmission path for cursor movement. */
    val transport: AirMouseTransport = AirMouseTransport.ADB,
    /** True while the Bluetooth HID mouse is actually paired-through to the TV. */
    val cursorViaBluetooth: Boolean = false,
    /** Paired Bluetooth devices the user can pick the TV from. */
    val btPeers: List<BtPeer> = emptyList(),
    /** Latest Bluetooth status / error line. */
    val btStatus: String = ""
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
    private val btMouse = BtMouseController(application.applicationContext)

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
    private var sensorListener: SensorEventListener? = null
    @Volatile private var lastAirMouseActivity = 0L

    /** Latest absolute orientation, in radians, from the rotation-vector sensor. */
    @Volatile private var currentYaw = 0f
    @Volatile private var currentPitch = 0f

    /** Calibration baseline captured by the one-time wizard. */
    @Volatile private var restYaw = 0f
    @Volatile private var restPitch = 0f
    /** Last target position already flushed as a delta. */
    @Volatile private var lastCursorPxX = 0f
    @Volatile private var lastCursorPxY = 0f
    /** True only on the very first rotation-vector frame after arming. */
    @Volatile private var firstFrame = true

    // Trackpad delta accumulators flushed at 20Hz (every 50ms) to avoid flooding the 32-bit STB
    private val pendingDx = AtomicInteger(0)
    private val pendingDy = AtomicInteger(0)

    private var flusherStarted = false

    init {
        startConnectionWatchdog()
        startTrackpadFlusher()
        readAirMousePrefs()
        viewModelScope.launch {
            btMouse.state.collect { bt ->
                _airMouseState.update {
                    it.copy(
                        cursorViaBluetooth = bt.connected,
                        btPeers = bt.paired,
                        btStatus = bt.status
                    )
                }
            }
        }
    }

    private fun readAirMousePrefs() {
        val app = getApplication<Application>()
        val prefs = runCatching {
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull() ?: return
        runCatching {
            val sensitivity = prefs.getFloat(KEY_AIR_SENSITIVITY, 1f)
            _airMouseState.value = _airMouseState.value.copy(
                sensitivity = sensitivity.coerceIn(0.5f, 3f),
                calibrated = prefs.getBoolean(KEY_AIR_CALIBRATED, false),
                showPointer = prefs.getBoolean(KEY_AIR_SHOW_POINTER, true),
                transport = if (prefs.getBoolean(KEY_AIR_BT_TRANSPORT, false))
                    AirMouseTransport.BLUETOOTH else AirMouseTransport.ADB
            )
            restYaw = prefs.getFloat(KEY_AIR_REST_YAW, 0f)
            restPitch = prefs.getFloat(KEY_AIR_REST_PITCH, 0f)
            lastCursorPxX = 0f
            lastCursorPxY = 0f
        }
    }

    private fun writeAirMousePrefs() {
        runCatching {
            getApplication<Application>()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_AIR_SENSITIVITY, _airMouseState.value.sensitivity)
                .putBoolean(KEY_AIR_CALIBRATED, _airMouseState.value.calibrated)
                .putBoolean(KEY_AIR_SHOW_POINTER, _airMouseState.value.showPointer)
                .putBoolean(KEY_AIR_BT_TRANSPORT, _airMouseState.value.transport == AirMouseTransport.BLUETOOTH)
                .putFloat(KEY_AIR_REST_YAW, restYaw)
                .putFloat(KEY_AIR_REST_PITCH, restPitch)
                .apply()
        }
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
                    val usingBt = _airMouseState.value.transport == AirMouseTransport.BLUETOOTH &&
                        btMouse.hasConnection()
                    if (usingBt) {
                        // Bluetooth HID: 1 report count == 1 cursor step on the TV.
                        val combinedX = rawX + leftoverX.getAndSet(0)
                        val combinedY = rawY + leftoverY.getAndSet(0)
                        val dx = combinedX / BT_MILLI_PER_REPORT
                        val dy = combinedY / BT_MILLI_PER_REPORT
                        leftoverX.set(combinedX - dx * BT_MILLI_PER_REPORT)
                        leftoverY.set(combinedY - dy * BT_MILLI_PER_REPORT)
                        if (dx != 0 || dy != 0) {
                            lastAirMouseActivity = System.currentTimeMillis()
                            btMouse.sendMove(dx.coerceIn(-100, 100), dy.coerceIn(-100, 100))
                        }
                    } else {
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
        val rotation = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || rotation == null) {
            _uiState.update {
                it.copy(statusMessage = "Air mouse unavailable on this phone")
            }
            return
        }
        if (sensorListener == null) {
            val listener = object : SensorEventListener {
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                override fun onSensorChanged(event: SensorEvent) {
                    handleRotationEvent(event)
                }
            }
            sensorListener = listener
            sm.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_GAME)
        }
        if (!flusherStarted) {
            flusherStarted = true
            startAirMouseFlusher()
        }
        _airMouseState.update { it.copy(armed = true) }
        lastAirMouseActivity = System.currentTimeMillis()
        firstFrame = true
        // The system crosshair only matters for ADB injection; with Bluetooth
        // HID the TV draws a real hardware cursor all by itself.
        if (_airMouseState.value.transport == AirMouseTransport.ADB) {
            viewModelScope.launch {
                runCatching { adb.setPointerOverlayEnabled(_airMouseState.value.showPointer) }
                runCatching { adb.wakePointer() }
            }
        } else {
            viewModelScope.launch { runCatching { btMouse.start() } }
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
        // Hide the TV crosshair again so it doesn't become a permanent eyesore.
        if (_airMouseState.value.showPointer &&
            _airMouseState.value.transport == AirMouseTransport.ADB
        ) {
            viewModelScope.launch { runCatching { adb.setPointerOverlayEnabled(false) } }
        }
    }

    /**
     * Absolute air mouse: the phone acts like a pointing stick.
     *
     * Each frame we read yaw/pitch from the rotation-vector sensor and convert
     * the **offset from the calibrated rest pose** into a target screen position.
     * We then send only the *delta* since the previous frame over ADB, so the
     * TV cursor follows the phone smoothly at 25Hz.
     */
    private fun handleRotationEvent(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rotMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
        val orientation = FloatArray(3)
        remapToDisplay(rotMatrix, orientation)
        currentYaw = orientation[0]
        currentPitch = orientation[1]
        if (!_airMouseState.value.armed) return

        val dYaw = wrapped(currentYaw - restYaw)
        val dPitch = wrapped(currentPitch - restPitch)
        val sensitivity = _airMouseState.value.sensitivity
        val targetX = dYaw * STEPS_PER_RADIAN * sensitivity
        val targetY = dPitch * STEPS_PER_RADIAN * sensitivity

        if (firstFrame) {
            // Skip the very first frame so the cursor doesn't teleport.
            firstFrame = false
            lastCursorPxX = targetX
            lastCursorPxY = targetY
            return
        }

        pendingAirX.addAndGet(((targetX - lastCursorPxX) * MILLI_SCALE).roundToInt())
        pendingAirY.addAndGet(((targetY - lastCursorPxY) * MILLI_SCALE).roundToInt())
        lastCursorPxX = targetX
        lastCursorPxY = targetY

        // Mirror the pointing pose on the on-phone visual (wraps like a ball).
        val vertical = (Math.PI.toFloat() / 2f)
        _airMouseState.update { state ->
            state.copy(
                pointerX = (dYaw / vertical).coerceIn(-1f, 1f),
                pointerY = (dPitch / vertical).coerceIn(-1f, 1f)
            )
        }
    }

    /** Applies the screen-rotation dependent axis remap, then reads yaw/pitch. */
    private fun remapToDisplay(rotMatrix: FloatArray, orientation: FloatArray) {
        val rotation = (getApplication<Application>()
            .getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
            ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        when (rotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rotMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, rotMatrix
            )
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rotMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, rotMatrix
            )
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rotMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, rotMatrix
            )
            else -> Unit
        }
        SensorManager.getOrientation(rotMatrix, orientation)
    }

    fun setAirMouseSensitivity(value: Float) {
        val next = value.coerceIn(0.5f, 3f)
        _airMouseState.update { it.copy(sensitivity = next) }
        writeAirMousePrefs()
    }

    fun togglePointerOverlay() {
        val next = !_airMouseState.value.showPointer
        _airMouseState.update { it.copy(showPointer = next) }
        writeAirMousePrefs()
        if (_airMouseState.value.transport == AirMouseTransport.ADB) {
            viewModelScope.launch { runCatching { adb.setPointerOverlayEnabled(next) } }
        }
    }

    // ---------------------------------------------------------------------
    // Bluetooth HID mouse
    // ---------------------------------------------------------------------

    /** Chooses how cursor movements reach the TV (persisted). */
    fun setAirMouseTransport(transport: AirMouseTransport) {
        _airMouseState.update { it.copy(transport = transport) }
        writeAirMousePrefs()
        if (transport == AirMouseTransport.BLUETOOTH) {
            btMouse.start()
        } else {
            // Crosshair overlay only matters for ADB injection.
            if (_airMouseState.value.armed && _airMouseState.value.showPointer) {
                viewModelScope.launch { runCatching { adb.setPointerOverlayEnabled(true) } }
            }
        }
    }

    /** Power-up / refresh of the Bluetooth HID layer. Returns true when ready. */
    fun startBluetoothMouse(): Boolean = btMouse.start()

    /** True when the app already holds the Bluetooth runtime permission. */
    fun hasBluetoothPermission(): Boolean = btMouse.hasPermission()

    fun refreshBtPeers() = btMouse.refreshPeers()

    fun connectBtPeer(peer: BtPeer) {
        val ok = btMouse.connectTo(peer)
        if (!ok) {
            _airMouseState.update { it.copy(btStatus = "Could not reach ${peer.name}") }
        }
    }

    fun disconnectBt() = btMouse.disconnect()

    /** Called after the runtime BLUETOOTH_CONNECT grant comes back. */
    fun onBluetoothPermissionResult() {
        viewModelScope.launch { btMouse.start() }
        refreshBtPeers()
    }

    /** Shown when BLUETOOTH_CONNECT is denied — BT mouse stays unavailable. */
    fun onBluetoothDenied() {
        _airMouseState.update {
            it.copy(btStatus = "Bluetooth permission denied — ADB cursor mode kept")
        }
    }

    // ---------------------------------------------------------------------
    // One-time calibration wizard
    // ---------------------------------------------------------------------

    /** Opens the one-time "point at the TV centre" setup screen. */
    fun openCalibration() {
        _airMouseState.update {
            it.copy(calStep = AirCalibrationStep.POINT_AT_TV)
        }
    }

    /** Cancels the wizard without changing the stored baseline. */
    fun cancelCalibration() {
        _airMouseState.update { it.copy(calStep = AirCalibrationStep.NONE) }
    }

    /**
     * Snapshots the phone's current pointing pose as the cursor's origin.
     * This is the heart of the one-time calibration: the TV cursor is
     * therefore centred on the STB whenever the user is pointing at it.
     */
    fun saveRestPosition() {
        val yaw = currentYaw
        val pitch = currentPitch
        runCatching {
            restYaw = yaw
            restPitch = pitch
            lastCursorPxX = 0f
            lastCursorPxY = 0f
            firstFrame = true
        }
        _airMouseState.update { it.copy(calibrated = true, calStep = AirCalibrationStep.DONE) }
        writeAirMousePrefs()
        lastAirMouseActivity = System.currentTimeMillis()
    }

    fun closeCalibration() {
        _airMouseState.update { it.copy(calStep = AirCalibrationStep.NONE) }
    }

    fun resetCalibration() {
        _airMouseState.update { it.copy(calibrated = false, calStep = AirCalibrationStep.NONE) }
        writeAirMousePrefs()
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
        runCatching { btMouse.stop() }
        adb.close()
    }

    companion object {
        private const val PREFS_NAME = "stb_prefs"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_AIR_SENSITIVITY = "air_sensitivity"
        private const val KEY_AIR_CALIBRATED = "air_calibrated"
        private const val KEY_AIR_SHOW_POINTER = "air_show_pointer"
        private const val KEY_AIR_BT_TRANSPORT = "air_bt_transport"
        private const val KEY_AIR_REST_YAW = "air_rest_yaw"
        private const val KEY_AIR_REST_PITCH = "air_rest_pitch"

        /** TV-cursor pixels per radian of phone yaw/pitch: 90° = ~700 px, comfortable. */
        private const val STEPS_PER_RADIAN = 440f
        private const val MILLI_SCALE = 1000
        /** Milli-units per Bluetooth HID report count (1 count ≈ 1 cursor step). */
        private const val BT_MILLI_PER_REPORT = 120
        private const val CURSOR_KEEPALIVE_MS = 3000L

        private fun readDarkModePref(app: Application): Boolean = runCatching {
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DARK_MODE, false)
        }.getOrDefault(false)

        /** Wraps any angle (or normalised pointer) into -pi..pi / -1..1. */
        private fun wrapped(value: Float): Float = when {
            value > Math.PI.toFloat() -> value - 2f * Math.PI.toFloat()
            value < -Math.PI.toFloat() -> value + 2f * Math.PI.toFloat()
            else -> value
        }
    }
}