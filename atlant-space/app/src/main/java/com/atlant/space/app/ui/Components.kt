package com.atlant.space.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atlant.space.app.R

/** The product logo + wordmark used in headers. */
@Composable
fun AtlantLogo(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Image(
            painter = painterResource(R.drawable.logo_atlant),
            contentDescription = null,
            modifier = Modifier.size(if (compact) 28.dp else 36.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column {
            Text("ATLANT", color = Palette.TextPrimary, fontWeight = FontWeight.Bold,
                fontSize = if (compact) 15.sp else 18.sp, letterSpacing = 3.sp)
            Text("SPACE", color = Palette.Accent, fontWeight = FontWeight.Medium,
                fontSize = if (compact) 10.sp else 12.sp, letterSpacing = 6.sp)
        }
    }
}

/** A flat surface card with the spec's hairline divider border. */
@Composable
fun AtlantCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (elevated) Palette.SurfaceElevated else Palette.Surface,
        ),
        border = BorderStroke(1.dp, Palette.Divider),
    ) { Box(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun SectionHeader(title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), color = Palette.TextSecondary, fontSize = 12.sp,
            letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
        action?.invoke()
    }
}

/** A labelled statistic tile (RAM, storage, counts). */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Boolean = false, sub: String? = null) {
    AtlantCard(modifier) {
        Column {
            Text(value, color = if (accent) Palette.Accent else Palette.TextPrimary,
                fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(label.uppercase(), color = Palette.TextSecondary, fontSize = 10.sp, letterSpacing = 1.5.sp)
            if (sub != null) Text(sub, color = Palette.TextSecondary, fontSize = 11.sp)
        }
    }
}

/** A thin lime progress meter over the divider track. */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        color = Palette.Accent,
        trackColor = Palette.Divider,
    )
}

/** A circular monogram for an app with no icon, using the first letter. */
@Composable
fun Monogram(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.firstOrNull()?.uppercase() ?: "?", color = Palette.Accent,
            fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
