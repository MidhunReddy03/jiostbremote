package com.stb.mastercontrol.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stb.mastercontrol.ui.theme.NeuButton
import com.stb.mastercontrol.ui.theme.DarkNeuColors
import com.stb.mastercontrol.ui.theme.LightNeuColors
import com.stb.mastercontrol.ui.theme.LocalNeuColors
import com.stb.mastercontrol.ui.theme.dashedBorder
import com.stb.mastercontrol.data.BtMouseController.BtPeer
import com.stb.mastercontrol.ui.theme.neumorphic
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
            CompositionLocalProvider(
                LocalNeuColors provides (if (isDarkMode) DarkNeuColors else LightNeuColors)
            ) {
                StbMasterControlApp(
                    state = state,
                    viewModel = viewModel,
                    isDarkMode = isDarkMode,
                    onToggleTheme = { viewModel.toggleDarkMode() }
                )
            }
        }
    }

    /**
     * Intercepts physical phone Volume Up/Down keys for blind TV volume control.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                viewModel.sendKey(24)
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                viewModel.sendKey(25)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}

@Composable
fun StbMasterControlApp(
    state: UiState,
    viewModel: MainViewModel,
    isDarkMode: Boolean,
    onToggleTheme: () -> Unit
) {
    val neu = LocalNeuColors.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val isSleeping = state.connectionStatus == ConnectionStatus.SLEEP_OR_OFFLINE
    val airState by viewModel.airMouseState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(neu.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 0a. Connection pill + dark-mode toggle share the top row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(1f))
            ConnectionStatusPill(
                status = state.connectionStatus,
                hostIp = state.hostIp,
                onReconnectClick = { viewModel.manualReconnect() }
            )
            Spacer(modifier = Modifier.width(10.dp))
            ThemeToggleButton(isDarkMode = isDarkMode, onToggle = onToggleTheme)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Sleep Mode / Disconnected Graceful Banner
        AnimatedVisibility(
            visible = isSleeping,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SleepModeBanner(
                onWakeClick = {
                    viewModel.sendKey(26) // Attempt Wake KeyEvent
                    viewModel.manualReconnect()
                }
            )
        }

        // 3. Main Content Area (Fades slightly when STB drops Wi-Fi in deep sleep)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .alpha(if (isSleeping) 0.65f else 1f)
        ) {
            when (selectedTab) {
                0 -> CoreRemoteTab(onKey = viewModel::sendKey)
                1 -> TrackpadKeyboardTab(
                    onRollDelta = viewModel::accumulateTrackpadDelta,
                    onTapClick = { viewModel.sendKey(66) },
                    onSendText = viewModel::pushSmartText,
                    onBackspace = { viewModel.sendKey(67) }
                )
                2 -> PowerUserDashboardTab(
                    state = state,
                    onPickApk = viewModel::sideloadApk,
                    onCleanCache = viewModel::cleanSystemCache,
                    onRefresh = viewModel::refreshDashboardData,
                    onLaunchApp = viewModel::launchTvApp
                )
                3 -> {
                    // Android 12+ needs a runtime grant to talk over Bluetooth.
                    val btLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        viewModel.onBluetoothPermissionResult()
                        if (!granted) viewModel.onBluetoothDenied()
                    }
                    AirMouseTab(
                        airState = airState,
                        connected = state.connectionStatus == ConnectionStatus.CONNECTED,
                        onToggleArm = viewModel::toggleAirMouse,
                        onSendKey = viewModel::sendKey,
                        onSensitivityChange = viewModel::setAirMouseSensitivity,
                        onOpenCalibration = viewModel::openCalibration,
                        onSaveRestPosition = viewModel::saveRestPosition,
                        onCancelCalibration = viewModel::cancelCalibration,
                        onFinishCalibration = viewModel::closeCalibration,
                        onTogglePointerOverlay = viewModel::togglePointerOverlay,
                        onTransportChange = viewModel::setAirMouseTransport,
                        onRequestBtPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            } else {
                                viewModel.onBluetoothPermissionResult()
                            }
                        },
                        onRefreshBtPeers = viewModel::refreshBtPeers,
                        onConnectBtPeer = { peer ->
                            if (viewModel.hasBluetoothPermission()) {
                                viewModel.connectBtPeer(peer)
                            } else {
                                btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            }
                        },
                        onDisconnectBt = viewModel::disconnectBt
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 4. Pill-Shaped Bottom Navigation Bar
        NeumorphicBottomTabBar(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it }
        )
    }
}

// ============================================================================
// THEME TOGGLE (Light <-> Dark)
// ============================================================================
@Composable
fun ThemeToggleButton(isDarkMode: Boolean, onToggle: () -> Unit) {
    val neu = LocalNeuColors.current
    NeuButton(
        onClick = onToggle,
        cornerRadius = 21.dp,
        backgroundColor = neu.Background,
        modifier = Modifier.size(42.dp)
    ) {
        Icon(
            imageVector = if (isDarkMode) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
            contentDescription = if (isDarkMode) "Switch to light theme" else "Switch to dark theme",
            tint = neu.TextPrimary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun ConnectionStatusPill(
    status: ConnectionStatus,
    hostIp: String,
    onReconnectClick: () -> Unit
) {
    val neu = LocalNeuColors.current
    val dotColor = when (status) {
        ConnectionStatus.CONNECTED -> Color(0xFF48BB78) // Soft Sage/Emerald Green
        ConnectionStatus.CONNECTING -> Color(0xFFECC94B) // Warm Amber
        ConnectionStatus.SLEEP_OR_OFFLINE -> Color(0xFFE57373) // Soft Red
    }
    val label = when (status) {
        ConnectionStatus.CONNECTED -> "Connected • $hostIp"
        ConnectionStatus.CONNECTING -> "Connecting to STB..."
        ConnectionStatus.SLEEP_OR_OFFLINE -> "Offline • Tap to Retry"
    }

    NeuButton(
        onClick = onReconnectClick,
        cornerRadius = 50.dp,
        modifier = Modifier
            .height(42.dp)
            .wrapContentWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                color = neu.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SleepModeBanner(onWakeClick: () -> Unit) {
    val neu = LocalNeuColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .neumorphic(isPressed = true, cornerRadius = 20.dp, backgroundColor = neu.SoftCoral.copy(alpha = 0.25f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "STB in Deep Sleep or Offline",
                color = neu.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Auto-retrying ADB • Wake via BT or retry",
                color = neu.TextSecondary,
                fontSize = 11.sp
            )
        }
        NeuButton(
            onClick = onWakeClick,
            cornerRadius = 16.dp,
            backgroundColor = neu.SoftCoral,
            modifier = Modifier.height(36.dp)
        ) {
            Text(
                text = "Wake STB",
                color = neu.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
        }
    }
}

// ============================================================================
// TAB 1: THE CORE REMOTE (Tactile D-Pad & Basics)
// ============================================================================
@Composable
fun CoreRemoteTab(onKey: (Int) -> Unit) {
    val neu = LocalNeuColors.current
    val view = LocalView.current

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Power Button (Large, recessed circular button -> keyevent 26)
        NeuButton(
            onClick = { onKey(26) },
            cornerRadius = 44.dp,
            isRecessedDefault = true,
            backgroundColor = neu.SoftCoral.copy(alpha = 0.35f),
            modifier = Modifier.size(84.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.PowerSettingsNew,
                contentDescription = "Power",
                tint = neu.TextPrimary,
                modifier = Modifier.size(32.dp)
            )
        }

        // Frosted Glass Gesture / D-Pad Squircle Area
        var dragX by remember { mutableFloatStateOf(0f) }
        var dragY by remember { mutableFloatStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 24.dp)
                .neumorphic(
                    isPressed = false,
                    cornerRadius = 36.dp,
                    backgroundColor = neu.GlassSurface
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onKey(66) // Center Select / OK
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            dragX = 0f
                            dragY = 0f
                        },
                        onDragEnd = {
                            val threshold = 48f
                            if (abs(dragX) > abs(dragY)) {
                                if (dragX > threshold) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onKey(22) // DPAD_RIGHT
                                } else if (dragX < -threshold) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onKey(21) // DPAD_LEFT
                                }
                            } else {
                                if (dragY > threshold) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onKey(20) // DPAD_DOWN
                                } else if (dragY < -threshold) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onKey(19) // DPAD_UP
                                }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragX += dragAmount.x
                            dragY += dragAmount.y
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Subtle directional cues for blind/peripheral orientation
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowUp,
                contentDescription = null,
                tint = neu.TextSecondary.copy(alpha = 0.4f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = neu.TextSecondary.copy(alpha = 0.4f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowLeft,
                contentDescription = null,
                tint = neu.TextSecondary.copy(alpha = 0.4f),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 20.dp)
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = neu.TextSecondary.copy(alpha = 0.4f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 20.dp)
            )

            // Recessed Center "OK" Indicator
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .neumorphic(isPressed = true, cornerRadius = 38.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "OK",
                    color = neu.TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp
                )
            }
        }

        // Quick Action Pills + Volume Rocker (Bottom Row)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back Pill (keyevent 4)
            NeuButton(
                onClick = { onKey(4) },
                cornerRadius = 28.dp,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = neu.TextPrimary
                )
            }

            // Home Pill (keyevent 3)
            NeuButton(
                onClick = { onKey(3) },
                cornerRadius = 28.dp,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Home,
                    contentDescription = "Home",
                    tint = neu.TextPrimary
                )
            }

            // Voice Assistant Pill (keyevent 231)
            NeuButton(
                onClick = { onKey(231) },
                cornerRadius = 28.dp,
                backgroundColor = neu.OceanicBlue.copy(alpha = 0.35f),
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = "Assistant",
                    tint = neu.TextPrimary
                )
            }

            // Dual Volume Rocker Pill (keyevent 24 / 25)
            Row(
                modifier = Modifier
                    .weight(1.3f)
                    .height(64.dp)
                    .neumorphic(isPressed = true, cornerRadius = 28.dp)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                NeuButton(
                    onClick = { onKey(25) },
                    cornerRadius = 22.dp,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VolumeDown,
                        contentDescription = "Vol -",
                        tint = neu.TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                NeuButton(
                    onClick = { onKey(24) },
                    cornerRadius = 22.dp,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VolumeUp,
                        contentDescription = "Vol +",
                        tint = neu.TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ============================================================================
// TAB 2: THE TRACKPAD & KEYBOARD (Smart Input)
// ============================================================================
@Composable
fun TrackpadKeyboardTab(
    onRollDelta: (Float, Float) -> Unit,
    onTapClick: () -> Unit,
    onSendText: (String) -> Unit,
    onBackspace: () -> Unit
) {
    val neu = LocalNeuColors.current
    val view = LocalView.current
    var inputText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Smart Text Push Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .neumorphic(cornerRadius = 28.dp)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "SMART TEXT PUSH",
                color = neu.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Recessed Text Input Field
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .neumorphic(isPressed = true, cornerRadius = 18.dp)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = "Paste URL or password...",
                            color = neu.TextSecondary.copy(alpha = 0.6f),
                            fontSize = 14.sp
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = neu.TextPrimary,
                            fontSize = 14.sp
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Backspace Key (keyevent 67)
                NeuButton(
                    onClick = onBackspace,
                    cornerRadius = 18.dp,
                    modifier = Modifier.size(50.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Backspace,
                        contentDescription = "Backspace",
                        tint = neu.TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Send Button
                NeuButton(
                    onClick = {
                        if (inputText.isNotEmpty()) {
                            onSendText(inputText)
                            inputText = ""
                        }
                    },
                    cornerRadius = 18.dp,
                    backgroundColor = neu.OceanicBlue,
                    modifier = Modifier
                        .height(50.dp)
                        .padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = "Send",
                        color = neu.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }

        // Precision Trackpad Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .neumorphic(
                    isPressed = true,
                    cornerRadius = 32.dp,
                    backgroundColor = neu.GlassSurface
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onTapClick()
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onRollDelta(dragAmount.x, dragAmount.y)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Outlined.TouchApp,
                    contentDescription = null,
                    tint = neu.TextSecondary.copy(alpha = 0.45f),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Glide to Move Cursor • Tap to Click",
                    color = neu.TextSecondary.copy(alpha = 0.65f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ============================================================================
// TAB 3: THE POWER USER DASHBOARD (ADB Tools & Sideloading)
// ============================================================================
@Composable
fun PowerUserDashboardTab(
    state: UiState,
    onPickApk: (Uri) -> Unit,
    onCleanCache: () -> Unit,
    onRefresh: () -> Unit,
    onLaunchApp: (String) -> Unit
) {
    val neu = LocalNeuColors.current
    val view = LocalView.current
    val apkPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) onPickApk(uri)
    }

    LaunchedEffect(Unit) {
        onRefresh()
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // 1. Sideload APK Drop-Zone Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(125.dp)
                .neumorphic(
                    isPressed = true,
                    cornerRadius = 28.dp,
                    backgroundColor = neu.OceanicBlue.copy(alpha = 0.16f)
                )
                .dashedBorder(
                    color = neu.OceanicBlue,
                    cornerRadius = 28.dp
                )
                .clip(RoundedCornerShape(28.dp))
                .clickable(enabled = state.sideloadProgress == null) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    apkPicker.launch("application/vnd.android.package-archive")
                }
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (state.sideloadProgress != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.statusMessage ?: "Installing APK...",
                        color = neu.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.sideloadProgress ?: 0f },
                        color = neu.OceanicBlue,
                        trackColor = neu.Background,
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.CloudUpload,
                        contentDescription = "Sideload APK",
                        tint = neu.TextPrimary,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Tap to Sideload .APK to Jio STB",
                        color = neu.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = state.statusMessage ?: "Pushes & installs silently via ADB",
                        color = neu.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 2. 2-Column Grid: Memory Monitor Widget & 1-Tap System Cleaner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(165.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Circular RAM Gauge Card
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .neumorphic(cornerRadius = 28.dp)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(86.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 10.dp.toPx()
                        drawArc(
                            color = neu.DarkShadow,
                            startAngle = 135f,
                            sweepAngle = 270f,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = neu.MutedSage,
                            startAngle = 135f,
                            sweepAngle = 270f * (1f - state.ramStats.usedFraction),
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${state.ramStats.freeMb}",
                            color = neu.TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "MB Free",
                            color = neu.TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Free RAM: ~${state.ramStats.freeMb} MB",
                    color = neu.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // 1-Tap System Cleaner Card
            NeuButton(
                onClick = onCleanCache,
                cornerRadius = 28.dp,
                backgroundColor = neu.MutedSage.copy(alpha = 0.25f),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .neumorphic(cornerRadius = 27.dp, backgroundColor = neu.Background),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CleaningServices,
                            contentDescription = "Clean Cache",
                            tint = neu.TextPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "1-Tap Cleaner",
                        color = neu.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Trim 1000M Cache",
                        color = neu.TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // 3. Quick App Launchers (Horizontal Scroll)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .neumorphic(cornerRadius = 28.dp)
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "QUICK APP LAUNCHERS",
                    color = neu.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Text(
                    text = "${state.installedApps.size} apps",
                    color = neu.TextSecondary,
                    fontSize = 11.sp
                )
            }

            if (state.installedApps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Connect to STB to load installed TV apps",
                        color = neu.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(state.installedApps, key = { it.packageName }) { app ->
                        NeuButton(
                            onClick = { onLaunchApp(app.packageName) },
                            cornerRadius = 20.dp,
                            modifier = Modifier
                                .width(92.dp)
                                .height(86.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(neu.OceanicBlue.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = app.label.take(2).uppercase(),
                                        color = neu.TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = app.label,
                                    color = neu.TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// TAB 4: THE AIR MOUSE (Phone gyro -> TV cursor)
// ============================================================================
@Composable
fun AirMouseTab(
    airState: AirMouseState,
    connected: Boolean,
    onToggleArm: () -> Unit,
    onSendKey: (Int) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onOpenCalibration: () -> Unit,
    onSaveRestPosition: () -> Unit,
    onCancelCalibration: () -> Unit,
    onFinishCalibration: () -> Unit,
    onTogglePointerOverlay: () -> Unit,
    onTransportChange: (AirMouseTransport) -> Unit,
    onRequestBtPermission: () -> Unit,
    onRefreshBtPeers: () -> Unit,
    onConnectBtPeer: (BtPeer) -> Unit,
    onDisconnectBt: () -> Unit
) {
    val neu = LocalNeuColors.current
    val animatedX by animateDpAsState(
        targetValue = (airState.pointerX * 74f).dp,
        animationSpec = tween(durationMillis = 40),
        label = "air_pointer_x"
    )
    val animatedY by animateDpAsState(
        targetValue = (airState.pointerY * 74f).dp,
        animationSpec = tween(durationMillis = 40),
        label = "air_pointer_y"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Gyro ball / pointing-pad visualiser
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 16.dp)
                .neumorphic(
                    isPressed = airState.armed,
                    cornerRadius = 36.dp,
                    backgroundColor = neu.GlassSurface
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(CircleShape)
                    .border(2.dp, neu.DarkShadow.copy(alpha = 0.35f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .offset(x = animatedX, y = animatedY)
                    .clip(CircleShape)
                    .background(if (airState.armed) neu.SoftCoral else neu.TextSecondary.copy(alpha = 0.6f))
            )
            Text(
                text = if (airState.calibrated) "Pointing set — calibrated" else "Calibration pending",
                color = neu.TextSecondary,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Sensitivity + visibility controls
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Sensitivity",
                    color = neu.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "x%.1f".format(airState.sensitivity),
                    color = neu.TextSecondary,
                    fontSize = 11.sp
                )
            }
            Slider(
                value = airState.sensitivity,
                onValueChange = onSensitivityChange,
                valueRange = 0.5f..3f,
                colors = SliderDefaults.colors(
                    thumbColor = neu.OceanicBlue,
                    activeTrackColor = neu.OceanicBlue.copy(alpha = 0.8f),
                    inactiveTrackColor = neu.DarkShadow.copy(alpha = 0.18f)
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Mouse,
                    contentDescription = null,
                    tint = neu.TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show cursor on TV",
                        color = neu.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Forces a crosshair overlay onto the STB screen",
                        color = neu.TextSecondary,
                        fontSize = 10.sp
                    )
                }
                Switch(
                    checked = airState.showPointer,
                    onCheckedChange = { onTogglePointerOverlay() }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ---- Transport selector: Bluetooth HID cursor vs ADB injection ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AirTransportPill(
                label = "Bluetooth",
                subtitle = "Real TV cursor",
                icon = Icons.Outlined.Bluetooth,
                selected = airState.transport == AirMouseTransport.BLUETOOTH,
                onClick = {
                    onTransportChange(AirMouseTransport.BLUETOOTH)
                    onRefreshBtPeers()
                },
                modifier = Modifier.weight(1f)
            )
            AirTransportPill(
                label = "ADB",
                subtitle = "Injection",
                icon = Icons.Outlined.ElectricalServices,
                selected = airState.transport == AirMouseTransport.ADB,
                onClick = { onTransportChange(AirMouseTransport.ADB) },
                modifier = Modifier.weight(1f)
            )
        }

        // ---- Bluetooth HID device picker ----
        if (airState.transport == AirMouseTransport.BLUETOOTH) {
            Spacer(modifier = Modifier.height(8.dp))
            BtHidCard(
                airState = airState,
                onConnectPeer = onConnectBtPeer,
                onDisconnect = onDisconnectBt
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // One-time calibration row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NeuButton(
                onClick = onOpenCalibration,
                cornerRadius = 24.dp,
                backgroundColor = if (airState.calibrated) neu.Background
                                  else neu.OceanicBlue.copy(alpha = 0.35f),
                modifier = Modifier.weight(1f).height(44.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (airState.calibrated) Icons.Outlined.Cached
                                      else Icons.Outlined.Build,
                        contentDescription = null,
                        tint = neu.TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (airState.calibrated) "Recalibrate" else "Calibrate",
                        color = neu.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(modifier = Modifier.height(1.dp))
            if (airState.calStep == AirCalibrationStep.POINT_AT_TV) {
                NeuButton(
                    onClick = onSaveRestPosition,
                    cornerRadius = 24.dp,
                    backgroundColor = neu.MutedSage.copy(alpha = 0.4f),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text(
                        text = "Save",
                        color = neu.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                NeuButton(
                    onClick = onCancelCalibration,
                    cornerRadius = 24.dp,
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text(
                        text = "Cancel",
                        color = neu.TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NeuButton(
                onClick = { onSendKey(4) },
                cornerRadius = 24.dp,
                modifier = Modifier.weight(1f).height(44.dp)
            ) { Text(text = "Back", color = neu.TextPrimary, fontSize = 12.sp) }
            NeuButton(
                onClick = { onSendKey(66) },
                cornerRadius = 24.dp,
                modifier = Modifier.weight(1f).height(44.dp)
            ) { Text(text = "OK", color = neu.TextPrimary, fontSize = 12.sp) }
            NeuButton(
                onClick = { onSendKey(3) },
                cornerRadius = 24.dp,
                modifier = Modifier.weight(1f).height(44.dp)
            ) { Text(text = "Home", color = neu.TextPrimary, fontSize = 12.sp) }
        }

        Spacer(modifier = Modifier.height(12.dp))

        NeuButton(
            onClick = onToggleArm,
            cornerRadius = 28.dp,
            backgroundColor = if (airState.armed) neu.SoftCoral.copy(alpha = 0.45f)
                              else neu.OceanicBlue.copy(alpha = 0.4f),
            modifier = Modifier.height(52.dp).fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Icon(
                    imageVector = if (airState.armed) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    tint = neu.TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (airState.armed) "Stop Air Mouse" else "Start Air Mouse",
                    color = neu.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = when {
                airState.transport == AirMouseTransport.BLUETOOTH &&
                    !airState.cursorViaBluetooth ->
                    "Bluetooth mode: pair the STB with this phone, then tap its name above."
                airState.transport == AirMouseTransport.BLUETOOTH ->
                    "Bluetooth HID live — the TV draws a real hardware cursor."
                airState.calStep == AirCalibrationStep.POINT_AT_TV ->
                    "Calibration: point the phone at the TV centre and tap Save."
                airState.calStep == AirCalibrationStep.DONE ->
                    "Calibration saved — the TV cursor is now centred when you point at the TV."
                !airState.calibrated ->
                    "Run the one-time calibration for centred pointing."
                airState.armed && connected -> "Move the phone like a pointing stick — the TV crosshair follows."
                airState.armed -> "Reconnecting to STB..."
                else -> "Start the air mouse — the TV shows a crosshair where you point."
            },
            color = neu.TextSecondary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
    }

    // Full-screen one-time calibration wizard overlay
    if (airState.calStep == AirCalibrationStep.POINT_AT_TV) {
        AirMouseCalibrationOverlay(
            onSaved = onSaveRestPosition,
            onDismiss = onCancelCalibration
        )
    } else if (airState.calStep == AirCalibrationStep.DONE) {
        AirMouseCalibrationFinishedOverlay(
            onFinished = onFinishCalibration
        )
    }
}

// ============================================================================
// AIR MOUSE: TRANSPORT PILL + BLUETOOTH HID DEVICE CARD
// ============================================================================
@Composable
private fun AirTransportPill(
    label: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val neu = LocalNeuColors.current
    NeuButton(
        onClick = onClick,
        cornerRadius = 18.dp,
        backgroundColor = if (selected) neu.OceanicBlue.copy(alpha = 0.35f) else neu.Background,
        modifier = modifier.height(52.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = neu.TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = label,
                    color = neu.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = subtitle,
                color = neu.TextSecondary,
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun BtHidCard(
    airState: AirMouseState,
    onConnectPeer: (BtPeer) -> Unit,
    onDisconnect: () -> Unit
) {
    val neu = LocalNeuColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .neumorphic(cornerRadius = 20.dp, backgroundColor = neu.GlassSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Mouse,
            contentDescription = null,
            tint = if (airState.cursorViaBluetooth) neu.MutedSage else neu.TextSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (airState.cursorViaBluetooth) {
                Text(
                    text = "Mouse connected — TV shows a real cursor",
                    color = neu.TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Keys, dashboard & sideload still go over ADB",
                    color = neu.TextSecondary,
                    fontSize = 9.sp
                )
            } else {
                Text(
                    text = "Pick your TV from the paired devices",
                    color = neu.TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (airState.btStatus.isNotEmpty()) airState.btStatus
                    else "Pair the phone on the STB first: Settings → Bluetooth",
                    color = neu.TextSecondary,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (airState.btPeers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(airState.btPeers) { peer ->
                        NeuButton(
                            onClick = { onConnectPeer(peer) },
                            cornerRadius = 14.dp,
                            backgroundColor = neu.OceanicBlue.copy(alpha = 0.3f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.AddLink,
                                    contentDescription = null,
                                    tint = neu.TextPrimary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = peer.name,
                                    color = neu.TextPrimary,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 110.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        if (airState.cursorViaBluetooth) {
            NeuButton(
                onClick = onDisconnect,
                cornerRadius = 16.dp,
                backgroundColor = neu.SoftCoral.copy(alpha = 0.35f),
                modifier = Modifier.height(40.dp)
            ) {
                Text(
                    text = "Off",
                    color = neu.TextPrimary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

@Composable
fun AirMouseCalibrationOverlay(onSaved: () -> Unit, onDismiss: () -> Unit) {
    val neu = LocalNeuColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .neumorphic(cornerRadius = 28.dp, backgroundColor = neu.Background)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "One-time calibration",
                color = neu.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "1. Sit where you normally sit.\n"
                    + "2. Point the phone straight at the centre of the TV.\n"
                    + "3. Tap Save.\n\n"
                    + "The TV cursor will now be centred whenever you point at the STB.",
                color = neu.TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeuButton(
                    onClick = onDismiss,
                    cornerRadius = 18.dp,
                    modifier = Modifier.height(42.dp)
                ) {
                    Text(
                        text = "Cancel",
                        color = neu.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                NeuButton(
                    onClick = onSaved,
                    cornerRadius = 18.dp,
                    backgroundColor = neu.OceanicBlue.copy(alpha = 0.5f),
                    modifier = Modifier.height(42.dp)
                ) {
                    Text(
                        text = "Save position",
                        color = neu.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AirMouseCalibrationFinishedOverlay(onFinished: () -> Unit) {
    val neu = LocalNeuColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .neumorphic(cornerRadius = 28.dp, backgroundColor = neu.Background)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Calibration saved ✓",
                color = neu.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Point at the TV again to re-centre. The setting is remembered\nbetween app launches.",
                color = neu.TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(14.dp))
            NeuButton(
                onClick = onFinished,
                cornerRadius = 18.dp,
                backgroundColor = neu.MutedSage.copy(alpha = 0.5f),
                modifier = Modifier.height(42.dp)
            ) {
                Text(
                    text = "Done",
                    color = neu.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

// ============================================================================
// BOTTOM PILL NAVIGATION BAR
// ============================================================================
@Composable
fun NeumorphicBottomTabBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val neu = LocalNeuColors.current
    val tabs = listOf(
        "Remote" to Icons.Outlined.SettingsRemote,
        "Trackpad" to Icons.Outlined.TouchApp,
        "Dashboard" to Icons.Outlined.Dashboard,
        "Air Mouse" to Icons.Outlined.Airplay
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .neumorphic(isPressed = true, cornerRadius = 32.dp)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEachIndexed { index, (title, icon) ->
            val isSelected = selectedTab == index
            NeuButton(
                onClick = { onTabSelected(index) },
                cornerRadius = 26.dp,
                isRecessedDefault = !isSelected,
                backgroundColor = if (isSelected) neu.OceanicBlue.copy(alpha = 0.4f) else Color.Transparent,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = neu.TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        color = neu.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}
