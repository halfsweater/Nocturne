package app.nocturne.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nocturne.ui.keyboard.Piano
import app.nocturne.ui.theme.Nocturne

// ------------------------------------------------------------------ icons (stroke-drawn, minimal)

private fun strokeIcon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.White), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
    }.build()

object NIcons {
    val Library = strokeIcon("library") {
        moveTo(5f, 4f); lineTo(5f, 20f)
        moveTo(9f, 4f); lineTo(9f, 20f)
        moveTo(13f, 5f); lineTo(17.5f, 19.5f)
        moveTo(4f, 20f); lineTo(20f, 20f)
    }
    val Tune = strokeIcon("tune") {
        moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 17f); lineTo(20f, 17f)
        moveTo(9f, 5f); lineTo(9f, 9f); moveTo(15f, 10f); lineTo(15f, 14f); moveTo(7f, 15f); lineTo(7f, 19f)
    }
    val Left = strokeIcon("left") { moveTo(14.5f, 6f); lineTo(8.5f, 12f); lineTo(14.5f, 18f) }
    val Right = strokeIcon("right") { moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f) }
    val Minus = strokeIcon("minus") { moveTo(6f, 12f); lineTo(18f, 12f) }
    val Plus = strokeIcon("plus") { moveTo(6f, 12f); lineTo(18f, 12f); moveTo(12f, 6f); lineTo(12f, 18f) }
    val Close = strokeIcon("close") { moveTo(7f, 7f); lineTo(17f, 17f); moveTo(17f, 7f); lineTo(7f, 17f) }
    val Back = strokeIcon("back") { moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val Play = strokeIcon("play") { moveTo(8f, 5.5f); lineTo(18f, 12f); lineTo(8f, 18.5f); close() }
    val Download = strokeIcon("download") {
        moveTo(12f, 4f); lineTo(12f, 15f); moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f)
        moveTo(5f, 19.5f); lineTo(19f, 19.5f)
    }
    val Check = strokeIcon("check") { moveTo(5f, 12.5f); lineTo(10f, 17f); lineTo(19f, 7f) }
    val Heart = strokeIcon("heart") {
        moveTo(12f, 19.5f)
        curveTo(5f, 14.5f, 3.5f, 11.5f, 3.5f, 8.8f)
        curveTo(3.5f, 6.4f, 5.4f, 4.5f, 7.8f, 4.5f)
        curveTo(9.6f, 4.5f, 11.2f, 5.6f, 12f, 7.2f)
        curveTo(12.8f, 5.6f, 14.4f, 4.5f, 16.2f, 4.5f)
        curveTo(18.6f, 4.5f, 20.5f, 6.4f, 20.5f, 8.8f)
        curveTo(20.5f, 11.5f, 19f, 14.5f, 12f, 19.5f)
        close()
    }
    val HeartFilled: ImageVector = ImageVector.Builder("heartFilled", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 19.5f)
            curveTo(5f, 14.5f, 3.5f, 11.5f, 3.5f, 8.8f)
            curveTo(3.5f, 6.4f, 5.4f, 4.5f, 7.8f, 4.5f)
            curveTo(9.6f, 4.5f, 11.2f, 5.6f, 12f, 7.2f)
            curveTo(12.8f, 5.6f, 14.4f, 4.5f, 16.2f, 4.5f)
            curveTo(18.6f, 4.5f, 20.5f, 6.4f, 20.5f, 8.8f)
            curveTo(20.5f, 11.5f, 19f, 14.5f, 12f, 19.5f)
            close()
        }
    }.build()
    val Info = strokeIcon("info") {
        moveTo(12f, 11f); lineTo(12f, 17f); moveTo(12f, 7.6f); lineTo(12f, 7.7f)
        moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f)
    }
}

// ------------------------------------------------------------------ buttons

@Composable
fun IconCircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = Nocturne.Silver,
    outlined: Boolean = true,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .then(if (outlined) Modifier.border(1.dp, Nocturne.Hairline, CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = Nocturne.SilverDim) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = color, modifier = modifier)
}

/**
 * Sustain pedal. Press and hold for momentary sustain (like a real pedal under the left
 * thumb); a quick tap latches it on until tapped again.
 */
@Composable
fun PedalButton(active: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(active)
    val change by rememberUpdatedState(onChange)
    val bg by animateColorAsState(if (active) Nocturne.Accent else Nocturne.Surface, tween(120), label = "pedal")
    val fg = if (active) Nocturne.Charcoal else Nocturne.Silver
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, if (active) Nocturne.Accent else Nocturne.Hairline, RoundedCornerShape(14.dp))
            .semantics { contentDescription = "Sustain pedal" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val wasLatched = current
                    if (!wasLatched) change(true)
                    var upTime = down.uptimeMillis
                    do {
                        val event = awaitPointerEvent()
                        event.changes.firstOrNull()?.let { upTime = it.uptimeMillis }
                    } while (event.changes.any { it.pressed })
                    // Uses the touch events' own timestamps, so a busy UI thread can't turn a tap into a hold.
                    val tap = upTime - down.uptimeMillis < 260
                    when {
                        wasLatched -> change(false)          // tap on a latched pedal releases it
                        tap -> Unit                          // quick tap: stay latched
                        else -> change(false)                // held: momentary
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(width = 26.dp, height = 12.dp)) {
                drawRoundRect(fg, topLeft = Offset(0f, size.height * 0.35f),
                    size = Size(size.width, size.height * 0.65f), cornerRadius = CornerRadius(6f, 6f))
                drawRect(fg, topLeft = Offset(size.width * 0.42f, 0f), size = Size(size.width * 0.16f, size.height * 0.4f))
            }
            Spacer(Modifier.height(3.dp))
            Text("SUSTAIN", style = MaterialTheme.typography.labelSmall, color = fg)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    valueText: String,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Overline(label)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = Nocturne.Silver)
        }
        val colors = SliderDefaults.colors(
            thumbColor = Nocturne.Ivory,
            activeTrackColor = Nocturne.Silver,
            inactiveTrackColor = Nocturne.Hairline,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.height(32.dp),
            colors = colors,
            thumb = {
                Box(Modifier.size(18.dp).clip(CircleShape).background(Nocturne.Ivory)
                    .border(3.dp, Nocturne.Charcoal.copy(alpha = 0.35f), CircleShape))
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(3.dp),
                    colors = colors,
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                )
            },
        )
    }
}

@Composable
fun ToggleChip(label: String, checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (checked) Nocturne.Silver else Color.Transparent, tween(150), label = "chip")
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, if (checked) Nocturne.Silver else Nocturne.Hairline, RoundedCornerShape(20.dp))
            .clickable(role = Role.Switch, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (checked) Nocturne.Charcoal else Nocturne.Silver)
    }
}

/**
 * Overview of all 88 keys with the visible window highlighted. Drag or tap to move
 * the playing area across octaves (the keys themselves are reserved for playing).
 */
@Composable
fun KeyboardMiniMap(
    firstWhite: Float,
    visibleWhite: Int,
    onSeek: (Float, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seek by rememberUpdatedState(onSeek)
    val visible by rememberUpdatedState(visibleWhite)
    Canvas(
        modifier
            .semantics { contentDescription = "Keyboard position" }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val center = pos.x / size.width * Piano.WHITE_COUNT
                    seek(center - visible / 2f, true)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val center = change.position.x / size.width * Piano.WHITE_COUNT
                    seek(center - visible / 2f, false)
                }
            }
    ) {
        val kw = size.width / Piano.WHITE_COUNT
        val h = size.height
        for (i in 0 until Piano.WHITE_COUNT) {
            drawRect(Nocturne.Ivory.copy(alpha = 0.55f), Offset(i * kw + 0.5f, 0f), Size(kw - 1f, h))
        }
        for (i in 0 until Piano.WHITE_COUNT) {
            val n = Piano.WHITE_NOTES[i] + 1
            if (n <= Piano.HIGHEST && Piano.isBlack(n)) {
                drawRect(Nocturne.Charcoal, Offset((i + 1) * kw - kw * 0.3f, 0f), Size(kw * 0.6f, h * 0.6f))
            }
        }
        val x = firstWhite * kw
        drawRect(Nocturne.Silver.copy(alpha = 0.22f), Offset(x, 0f), Size(visibleWhite * kw, h))
        drawRoundRect(Nocturne.Silver, Offset(x, 0f), Size(visibleWhite * kw, h),
            cornerRadius = CornerRadius(3f, 3f), style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Nocturne.Hairline))
}

@Composable
fun SmallSpacer(w: Dp = 8.dp) = Spacer(Modifier.width(w))
