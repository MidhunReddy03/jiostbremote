package com.stb.mastercontrol.ui.theme

import android.graphics.BlurMaskFilter
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object NeuColors {
    val Background = Color(0xFFF0F4F8)       // Off-white / light cool grey
    val GlassSurface = Color(0xCCFAFBFD)     // Frosted glass overlay
    val OceanicBlue = Color(0xFFA8C0D8)      // Pastel oceanic blue accent
    val SoftCoral = Color(0xFFF4C2C2)        // Soft coral/peach accent
    val MutedSage = Color(0xFFB5C9B8)        // Muted sage green accent
    val TextPrimary = Color(0xFF2D3748)      // Deep charcoal (No #000000)
    val TextSecondary = Color(0xFF64748B)    // Soft grey-blue

    val LightShadow = Color(0xFFFFFFFF)      // Top-left bright highlight
    val DarkShadow = Color(0xFFD1D9E6)       // Bottom-right soft shadow
}

/**
 * Memory-efficient Neumorphic dual-shadow modifier.
 * Caches native Paint objects in `remember` to prevent GC allocations during recomposition.
 */
@Composable
fun Modifier.neumorphic(
    isPressed: Boolean = false,
    cornerRadius: Dp = 24.dp,
    elevation: Dp = 8.dp,
    backgroundColor: Color = NeuColors.Background
): Modifier {
    val lightPaint = remember { Paint() }
    val darkPaint = remember { Paint() }

    return this
        .drawBehind {
            val radiusPx = cornerRadius.toPx()
            val elevationPx = if (isPressed) (elevation / 2).toPx() else elevation.toPx()
            val blurPx = (elevationPx * 1.5f).coerceAtLeast(1f)

            // Flip shadow offsets when pressed to simulate a recessed/pushed-in surface
            val offsetMultiplier = if (isPressed) -0.5f else 1f
            val offsetPx = elevationPx * offsetMultiplier

            lightPaint.asFrameworkPaint().apply {
                isAntiAlias = true
                color = NeuColors.LightShadow.toArgb()
                maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            }
            darkPaint.asFrameworkPaint().apply {
                isAntiAlias = true
                color = NeuColors.DarkShadow.toArgb()
                maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            }

            drawIntoCanvas { canvas ->
                // Top-Left Light Highlight
                canvas.save()
                canvas.translate(-offsetPx, -offsetPx)
                canvas.drawRoundRect(0f, 0f, size.width, size.height, radiusPx, radiusPx, lightPaint)
                canvas.restore()

                // Bottom-Right Dark Shadow
                canvas.save()
                canvas.translate(offsetPx, offsetPx)
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
    backgroundColor: Color = NeuColors.Background,
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