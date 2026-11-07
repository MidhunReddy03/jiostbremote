package com.stb.mastercontrol.ui

import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stb.mastercontrol.ui.theme.NeuButton
import com.stb.mastercontrol.ui.theme.NeuColors
import com.stb.mastercontrol.ui.theme.dashedBorder
import com.stb.mastercontrol.ui.theme.neumorphic
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            StbMasterControlApp(state = state, viewModel = viewModel)
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
fun StbMasterControlApp(state: UiState, viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val isSleeping = state.connectionStatus == ConnectionStatus.SLEEP_OR_OFFLINE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NeuColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Minimalist Connection State Pill
        ConnectionStatusPill(
            status = state.connectionStatus,
            hostIp = state.hostIp,
            onReconnectClick = { viewModel.manualReconnect() }
        )

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

@Composable
fun ConnectionStatusPill(
    status: ConnectionStatus,
    hostIp: String,
    onReconnectClick: () -> Unit
) {
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
                color = NeuColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SleepModeBanner(onWakeClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .neumorphic(isPressed = true, cornerRadius = 20.dp, backgroundColor = NeuColors.SoftCoral.copy(alpha = 0.25f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "STB in Deep Sleep or Offline",
                color = NeuColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Auto-retrying ADB • Wake via BT or retry",
                color = NeuColors.TextSecondary,
                fontSize = 11.sp
            )
        }
        NeuButton(
            onClick = onWakeClick,
            cornerRadius = 16.dp,
            backgroundColor = NeuColors.SoftCoral,
            modifier = Modifier.height(36.dp)
        ) {
            Text(
                text = "Wake STB",
                color = NeuColors.TextPrimary,
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
            backgroundColor = NeuColors.SoftCoral.copy(alpha = 0.35f),
            modifier = Modifier.size(84.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.PowerSettingsNew,
                contentDescription = "Power",
                tint = NeuColors.TextPrimary,
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
                    backgroundColor = NeuColors.GlassSurface
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
                tint = NeuColors.TextSecondary.copy(alpha = 0.4f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = NeuColors.TextSecondary.copy(alpha = 0.4f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowLeft,
                contentDescription = null,
                tint = NeuColors.TextSecondary.copy(alpha = 0.4f),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 20.dp)
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = NeuColors.TextSecondary.copy(alpha = 0.4f),
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
                    color = NeuColors.TextSecondary,
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
                    tint = NeuColors.TextPrimary
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
                    tint = NeuColors.TextPrimary
                )
            }

            // Voice Assistant Pill (keyevent 231)
            NeuButton(
                onClick = { onKey(231) },
                cornerRadius = 28.dp,
                backgroundColor = NeuColors.OceanicBlue.copy(alpha = 0.35f),
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = "Assistant",
                    tint = NeuColors.TextPrimary
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
                        tint = NeuColors.TextPrimary,
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
                        tint = NeuColors.TextPrimary,
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
                color = NeuColors.TextSecondary,
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
                            color = NeuColors.TextSecondary.copy(alpha = 0.6f),
                            fontSize = 14.sp
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = NeuColors.TextPrimary,
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
                        tint = NeuColors.TextPrimary,
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
                    backgroundColor = NeuColors.OceanicBlue,
                    modifier = Modifier
                        .height(50.dp)
                        .padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = "Send",
                        color = NeuColors.TextPrimary,
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
                    backgroundColor = NeuColors.GlassSurface
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
                    tint = NeuColors.TextSecondary.copy(alpha = 0.45f),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Glide to Move Cursor • Tap to Click",
                    color = NeuColors.TextSecondary.copy(alpha = 0.65f),
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
                    backgroundColor = NeuColors.OceanicBlue.copy(alpha = 0.16f)
                )
                .dashedBorder(
                    color = NeuColors.OceanicBlue,
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
                        color = NeuColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.sideloadProgress ?: 0f },
                        color = NeuColors.OceanicBlue,
                        trackColor = NeuColors.Background,
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
                        tint = NeuColors.TextPrimary,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Tap to Sideload .APK to Jio STB",
                        color = NeuColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = state.statusMessage ?: "Pushes & installs silently via ADB",
                        color = NeuColors.TextSecondary,
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
                            color = NeuColors.DarkShadow,
                            startAngle = 135f,
                            sweepAngle = 270f,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = NeuColors.MutedSage,
                            startAngle = 135f,
                            sweepAngle = 270f * (1f - state.ramStats.usedFraction),
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${state.ramStats.freeMb}",
                            color = NeuColors.TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "MB Free",
                            color = NeuColors.TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Free RAM: ~${state.ramStats.freeMb} MB",
                    color = NeuColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // 1-Tap System Cleaner Card
            NeuButton(
                onClick = onCleanCache,
                cornerRadius = 28.dp,
                backgroundColor = NeuColors.MutedSage.copy(alpha = 0.25f),
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
                            .neumorphic(cornerRadius = 27.dp, backgroundColor = NeuColors.Background),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CleaningServices,
                            contentDescription = "Clean Cache",
                            tint = NeuColors.TextPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "1-Tap Cleaner",
                        color = NeuColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Trim 1000M Cache",
                        color = NeuColors.TextSecondary,
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
                    color = NeuColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Text(
                    text = "${state.installedApps.size} apps",
                    color = NeuColors.TextSecondary,
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
                        color = NeuColors.TextSecondary,
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
                                        .background(NeuColors.OceanicBlue.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = app.label.take(2).uppercase(),
                                        color = NeuColors.TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = app.label,
                                    color = NeuColors.TextPrimary,
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
// BOTTOM PILL NAVIGATION BAR
// ============================================================================
@Composable
fun NeumorphicBottomTabBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabs = listOf(
        "Remote" to Icons.Outlined.SettingsRemote,
        "Trackpad" to Icons.Outlined.TouchApp,
        "Dashboard" to Icons.Outlined.Dashboard
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
                backgroundColor = if (isSelected) NeuColors.OceanicBlue.copy(alpha = 0.4f) else Color.Transparent,
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
                        tint = NeuColors.TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        color = NeuColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}