package com.stb.mastercontrol.ui.theme

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class NeuPalette(
    val Background: Color,
    val GlassSurface: Color,
    val OceanicBlue: Color,
    val SoftCoral: Color,
    val MutedSage: Color,
    val TextPrimary: Color,
    val TextSecondary: Color,
    val LightShadow: Color,
    val DarkShadow: Color
)

/** Light / "White" day theme — the original design. */
val LightNeuColors = NeuPalette(
    Background = Color(0xFFF0F4F8),          // Off-white / light cool grey
    GlassSurface = Color(0xCCFAFBFD),        // Frosted glass overlay
    OceanicBlue = Color(0xFFA8C0D8),         // Pastel oceanic blue accent
    SoftCoral = Color(0xFFF4C2C2),           // Soft coral/peach accent
    MutedSage = Color(0xFFB5C9B8),           // Muted sage green accent
    TextPrimary = Color(0xFF2D3748),         // Deep charcoal (No #000000)
    TextSecondary = Color(0xFF64748B),       // Soft grey-blue
    LightShadow = Color(0xFFFFFFFF),         // Top-left bright highlight
    DarkShadow = Color(0xFFD1D9E6)           // Bottom-right soft shadow
)

/** Dark / "Night" theme — same neumorphic structure, inverted luminance. */
val DarkNeuColors = NeuPalette(
    Background = Color(0xFF151A22),          // Near-black cool slate
    GlassSurface = Color(0xCC1C222C),        // Frosted glass overlay (dark)
    OceanicBlue = Color(0xFF3D5A80),         // Desaturated ocean accent
    SoftCoral = Color(0xFF7A4A4A),           // Muted coral accent
    MutedSage = Color(0xFF3A4C41),           // Muted sage green accent
    TextPrimary = Color(0xFFE5E9F0),         // Soft off-white
    TextSecondary = Color(0xFF8D99A8),       // Soft grey-blue
    LightShadow = Color(0xFF232B37),         // Raised edge highlight
    DarkShadow = Color(0xFF0A0D12)           // Recessed edge shadow
)

val LocalNeuColors = staticCompositionLocalOf { LightNeuColors }

/**
 * Memory-efficient Neumorphic dual-shadow modifier.
 * Caches native Paint objects in `remember` to prevent GC allocations during recomposition.
 */
@Composable
fun Modifier.neumorphic(
    isPressed: Boolean = false,
    cornerRadius: Dp = 24.dp,
    elevation: Dp = 8.dp,
    backgroundColor: Color = LocalNeuColors.current.Background
): Modifier {
    val colors = LocalNeuColors.current
    val lightPaint = remember { Paint() }
    val darkPaint = remember { Paint() }

    return this
        .drawBehind {
            val radiusPx = cornerRadius.toPx()
            val elevationPx = if (isPressed) (elevation / 2).toPx() else elevation.toPx()

            lightPaint.asFrameworkPaint().apply {
                isAntiAlias = true
                color = colors.LightShadow.toArgb()
            }
            darkPaint.asFrameworkPaint().apply {
                isAntiAlias = true
                color = colors.DarkShadow.toArgb()
            }

            drawIntoCanvas { canvas ->
                // Top-Left Light Highlight (drawing with offset)
                canvas.save()
                canvas.translate(0f, 0f)
                canvas.drawRoundRect(0f, 0f, size.width, size.height, radiusPx, radiusPx, lightPaint)
                canvas.restore()

                // Bottom-Right Dark Shadow (drawing with offset to simulate depth)
                canvas.save()
                canvas.translate(elevationPx, elevationPx)
                canvas.drawRoundRect(0f, 0f, size.width, size.height, radiusPx, radiusPx, darkPaint)
                canvas.restore()
            }
        }
        .background(backgroundColor, RoundedCornerShape(cornerRadius))
}

/**
 * Dashed border modifier for the APK Sideload zone.
 */
fun Modifier.dashedBorder(
    color: Color,
    cornerRadius: Dp = 24.dp,
    strokeWidth: Dp = 2.dp,
    dashLength: Dp = 10.dp
): Modifier = this.drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(cornerRadius.toPx()),
        style = Stroke(
            width = strokeWidth.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(dashLength.toPx(), dashLength.toPx()), 0f
            )
        )
    )
}

/**
 * Reusable Tactile Button that triggers physical haptic feedback and animates depth on press.
 */
@Composable
fun NeuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    backgroundColor: Color = LocalNeuColors.current.Background,
    isRecessedDefault: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 80),
        label = "neu_scale"
    )
    val shape: Shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .scale(scale)
            .neumorphic(
                isPressed = isPressed || isRecessedDefault,
                cornerRadius = cornerRadius,
                backgroundColor = backgroundColor
            )
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            },
        contentAlignment = Alignment.Center,
        content = content
    )
}