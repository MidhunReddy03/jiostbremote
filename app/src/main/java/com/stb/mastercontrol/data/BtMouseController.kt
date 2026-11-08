package com.stb.mastercontrol.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bluetooth HID mouse.
 *
 * Registers this phone as a real Bluetooth HID mouse (BluetoothHidDevice profile,
 * API 28+) so the STB sees it as a piece of hardware and draws the cursor
 * natively — no fake ADB injection, no crosshair hack. Every movement is sent
 * as an HID input report on the interrupt channel.
 */
class BtMouseController(private val context: Context) {

    data class BtState(
        val supported: Boolean = true,
        val adapterOn: Boolean = false,
        val permissionGranted: Boolean = false,
        val registered: Boolean = false,
        val connected: Boolean = false,
        val connectedName: String? = null,
        val paired: List<BtPeer> = emptyList(),
        val status: String = ""
    )

    data class BtPeer(val mac: String, val name: String)

    private val _state = MutableStateFlow(BtState())
    val state: StateFlow<BtState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = AtomicBoolean(false)
    private var adapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private val pendingPress = AtomicBoolean(false)

    /**
     * Standard 3-button relative mouse descriptor (X, Y and wheel are 8-bit
     * signed relative values) plus one padding bit triplet. Reports are 4
     * bytes: [buttons, dx, dy, wheel].
     */
    private val mouseDescriptor: ByteArray = intArrayOf(
        0x05, 0x01,     // Usage Page (Generic Desktop)
        0x09, 0x02,     // Usage (Mouse)
        0xA1, 0x01,     // Collection (Application)
        0x09, 0x01,     //   Usage (Pointer)
        0xA1, 0x00,     //   Collection (Physical)
        0x05, 0x09,     //     Usage Page (Buttons)
        0x19, 0x01,     //     Usage Minimum (Button 1)
        0x29, 0x03,     //     Usage Maximum (Button 3)
        0x15, 0x00,     //     Logical Minimum (0)
        0x25, 0x01,     //     Logical Maximum (1)
        0x95, 0x03,     //     Report Count (3)
        0x75, 0x01,     //     Report Size (1)
        0x81, 0x02,     //     Input (Data, Var, Abs)
        0x95, 0x01,     //     Report Count (1)
        0x75, 0x05,     //     Report Size (5)
        0x81, 0x03,     //     Input (Const, Var, Abs) — 5 bits padding
        0x05, 0x01,     //     Usage Page (Generic Desktop)
        0x09, 0x30,     //     Usage (X)
        0x09, 0x31,     //     Usage (Y)
        0x15, 0x81,     //     Logical Minimum (-127)
        0x25, 0x7F,     //     Logical Maximum (127)
        0x75, 0x08,     //     Report Size (8)
        0x95, 0x02,     //     Report Count (2)
        0x81, 0x06,     //     Input (Data, Var, Rel)
        0x09, 0x38,     //     Usage (Wheel)
        0x15, 0x81,     //     Logical Minimum (-127)
        0x25, 0x7F,     //     Logical Maximum (127)
        0x75, 0x08,     //     Report Size (8)
        0x95, 0x01,     //     Report Count (1)
        0x81, 0x06,     //     Input (Data, Var, Rel)
        0xC0,           //   End Collection (Physical)
        0xC0            // End Collection (Application)
    ).map { it.toByte() }.toByteArray()

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            update { it.copy(registered = registered) }
            if (registered) refreshPeers()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    update {
                        it.copy(
                            connected = true,
                            connectedName = device?.name ?: device?.address
                        )
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_DISCONNECTING -> {
                    if (host?.address == device?.address) {
                        host = null
                        update { it.copy(connected = false, connectedName = null) }
                    }
                }
                else -> update { it.copy(connected = false, connectedName = null) }
            }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val hid = proxy as? BluetoothHidDevice ?: return
            hidDevice = hid
            register()
        }

        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
            update { it.copy(registered = false, connected = false, connectedName = null) }
        }
    }

    /** Validates permissions/sensors/hardware, then grabs the HID device profile. */
    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return hasConnection()
        val granted = hasPermission()
        val sdkOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P // 28
        adapter = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()
        val a = adapter
        val supported = sdkOk && a != null
        update {
            it.copy(
                supported = supported,
                permissionGranted = granted,
                adapterOn = supported && runCatching { a?.isEnabled }.getOrDefault(false) == true
            )
        }
        if (!supported || !granted) return false
        return try {
            a!!.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        } catch (t: Throwable) {
            update { it.copy(status = "Bluetooth error: ${t.message}") }
            false
        }
    }

    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasConnection(): Boolean = _state.value.connected

    private fun register() {
        val hid = hidDevice
        if (hid == null) return
        val ok = runCatching {
            hid.registerApp(
                BluetoothHidDeviceAppSdpSettings(
                    "STB Mouse",
                    "Jio STB air mouse",
                    "STB Control",
                    BluetoothHidDevice.SUBCLASS1_MOUSE,
                    mouseDescriptor
                ),
                null,
                null,
                context.mainExecutor,
                hidCallback
            )
        }.getOrDefault(false)
        update { if (ok) it.copy(registered = true, status = "") else it.copy(status = "Registration refused") }
    }

    /** Lists paired devices so the user can pick the STB. */
    fun refreshPeers() {
        val a = adapter ?: return
        val peers = runCatching {
            a.bondedDevices.orEmpty().map { dev ->
                BtPeer(dev.address, dev.name ?: dev.address)
            }.sortedBy { it.name }
        }.getOrDefault(emptyList())
        if (peers.isEmpty()) {
            update { it.copy(paired = peers, status = "No paired device — pair on the STB first") }
        } else {
            update { it.copy(paired = peers, status = if (it.status.startsWith("No paired")) "" else it.status) }
        }
    }

    /** Initiates the HID connection to the STB (as the device role). */
    fun connectTo(peer: BtPeer): Boolean {
        val hid = hidDevice ?: return false
        val device = adapter?.getRemoteDevice(peer.mac) ?: return false
        return runCatching { hid.connect(device) }.getOrDefault(false)
    }

    fun disconnect() {
        val dev = host ?: return
        runCatching { hidDevice?.disconnect(dev) }
    }

    fun stop() {
        runCatching { hidDevice?.unregisterApp() }
        runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice) }
        hidDevice = null
        host = null
        started.set(false)
        update { it.copy(registered = false, connected = false, connectedName = null) }
        scope.cancel()
    }

    /** Queued so we never drop a fast tilt event; flushed from the scope. */
    fun sendMove(dx: Int, dy: Int, buttons: Int = 0) {
        scope.launch {
            val host = host ?: return@launch
            runCatching {
                hidDevice?.sendReport(
                    host, 0,
                    byteArrayOf(
                        (buttons and 0xFF).toByte(),
                        dx.coerceIn(-127, 127).toByte(),
                        dy.coerceIn(-127, 127).toByte(),
                        0
                    )
                )
            }
        }
    }

    /** Full HID press-and-release cycle for the left button. */
    fun sendLeftClick() {
        scope.launch {
            val host = host ?: return@launch
            runCatching {
                hidDevice?.sendReport(host, 0, byteArrayOf(1, 0, 0, 0))
            }
            kotlinx.coroutines.delay(60)
            runCatching {
                hidDevice?.sendReport(host, 0, byteArrayOf(0, 0, 0, 0))
            }
        }
    }

    /** Releases the button if the user switches tabs mid-press. */
    fun clearButtonState() {
        if (pendingPress.getAndSet(false)) sendMove(0, 0, 0)
    }

    private fun update(action: (BtState) -> BtState) {
        _state.value = action(_state.value)
    }
}
