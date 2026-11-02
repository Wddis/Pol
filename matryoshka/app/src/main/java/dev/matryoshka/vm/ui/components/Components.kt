package dev.matryoshka.vm.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.matryoshka.vm.core.model.VmState
import dev.matryoshka.vm.ui.theme.Brand

/**
 * The app's signature visual: a phone silhouette with another phone nested inside it
 * ("a device inside the device"). The inner screen glows while the VM is running.
 */
@Composable
fun NestedDevice(
    accent: Color,
    state: VmState,
    modifier: Modifier = Modifier,
) {
    val running = state is VmState.Running
    val busy = state is VmState.Preparing || state is VmState.Booting || state is VmState.Stopping
    val t = rememberInfiniteTransition(label = "device")
    val pulse by t.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "sweep")
    val outline = MaterialTheme.colorScheme.outline
    val body = MaterialTheme.colorScheme.surfaceContainerHighest
    val screenOff = MaterialTheme.colorScheme.surfaceContainerLowest

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val r = w * 0.16f
        // Outer phone
        drawRoundRect(body, cornerRadius = CornerRadius(r, r))
        drawRoundRect(outline, cornerRadius = CornerRadius(r, r), style = Stroke(width = 1.5.dp.toPx()))
        // Outer screen
        val inset = w * 0.07f
        val screenR = r * 0.75f
        drawRoundRect(
            screenOff, topLeft = Offset(inset, inset), size = Size(w - inset * 2, h - inset * 2),
            cornerRadius = CornerRadius(screenR, screenR),
        )
        // Inner (virtual) phone
        val iw = w * 0.56f
        val ih = h * 0.62f
        val ix = (w - iw) / 2
        val iy = (h - ih) / 2
        val ir = iw * 0.16f
        val alpha = when {
            running -> 1f
            busy -> pulse
            else -> 0.35f
        }
        drawRoundRect(
            Brush.linearGradient(listOf(accent.copy(alpha = alpha), accent.copy(alpha = alpha * 0.45f)), Offset(ix, iy), Offset(ix + iw, iy + ih)),
            topLeft = Offset(ix, iy), size = Size(iw, ih), cornerRadius = CornerRadius(ir, ir),
        )
        if (busy) {
            val y = iy + ih * sweep
            drawLine(Color.White.copy(alpha = 0.55f), Offset(ix + ir * 0.4f, y), Offset(ix + iw - ir * 0.4f, y), strokeWidth = 2.dp.toPx())
        }
        // Inner punch-hole camera
        drawCircle(Color.Black.copy(alpha = 0.55f), radius = iw * 0.04f, center = Offset(w / 2, iy + ih * 0.07f))
        // Outer punch-hole camera
        drawCircle(Color.Black, radius = w * 0.03f, center = Offset(w / 2, inset + w * 0.06f))
    }
}

@Composable
fun StateChip(state: VmState, modifier: Modifier = Modifier) {
    val (label, color) = when (state) {
        is VmState.Running -> "Running" to Brand.Ok
        is VmState.Booting -> "Booting" to Brand.Amber
        is VmState.Preparing -> "Preparing" to Brand.Amber
        is VmState.Stopping -> "Stopping" to Brand.Amber
        is VmState.Failed -> "Error" to Brand.Error
        VmState.Stopped -> "Off" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val bg by animateColorAsState(color.copy(alpha = 0.14f), label = "chipBg")
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        content = content,
    )
}

@Composable
fun ListRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

@Composable
fun SelectableCard(
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val border by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "border",
    )
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainer,
        label = "bg",
    )
    Box(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .border(if (selected) 2.dp else 1.dp, border, MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
    ) { content() }
}

@Composable
fun Meter(label: String, value: String, fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.size(6.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(color))
        }
    }
}

@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        NestedDevice(MaterialTheme.colorScheme.primary, VmState.Stopped, Modifier.size(width = 96.dp, height = 172.dp))
        Spacer(Modifier.size(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.size(20.dp))
        action()
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
