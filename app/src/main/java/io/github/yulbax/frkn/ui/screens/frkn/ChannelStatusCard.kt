package io.github.yulbax.frkn.ui.screens.frkn

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yulbax.frkn.R
import io.github.yulbax.frkn.data.ConnectionType
import io.github.yulbax.frkn.ui.components.ConnectionTypeIcon
import io.github.yulbax.frkn.ui.components.CountryFlag
import kotlin.math.abs
import kotlin.math.ceil

@Composable
internal fun ChannelStatusCard(
    type: ConnectionType,
    active: Boolean,
    up: Boolean,
    latencyMs: Int,
    country: String,
    shape: Shape,
    modifier: Modifier = Modifier,
    reachable: Int = 0,
    total: Int = 0,
    checking: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val dotColor = when {
        !active -> MaterialTheme.colorScheme.outline
        up -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.error
    }
    val statusLine = when {
        !active -> stringResource(R.string.channel_status_off)
        up -> stringResource(R.string.channel_status_online, latencyMs)
        else -> stringResource(R.string.channel_status_no_internet)
    }
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(type.label, style = MaterialTheme.typography.titleSmall)
                    Text("·", style = MaterialTheme.typography.titleSmall)
                    ConnectionTypeIcon(type, Modifier.size(16.dp))
                }
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                type == ConnectionType.BYEDPI && active && up ->
                    SignalBars(reachable, total, checking)
                active && up && country.isNotEmpty() -> CountryFlag(country)
            }
        }
    }
}

private const val SIGNAL_SEGMENTS = 7

@Composable
private fun SignalBars(reachable: Int, total: Int, checking: Boolean) {
    if (!checking && total <= 0) return
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Box(modifier = Modifier.padding(6.dp), contentAlignment = Alignment.Center) {
            val inactive = MaterialTheme.colorScheme.outlineVariant
            val ratio = if (total > 0) reachable.toFloat() / total else 0f
            val activeBars = ceil(ratio * SIGNAL_SEGMENTS).toInt().coerceIn(0, SIGNAL_SEGMENTS)
            val fillColor = when {
                ratio >= 0.8f -> Color(0xFF4CAF50)
                ratio >= 0.4f -> Color(0xFFFFC107)
                else -> MaterialTheme.colorScheme.error
            }

            val wave = if (checking) {
                val highlight = MaterialTheme.colorScheme.onSurfaceVariant
                val transition = rememberInfiniteTransition(label = "byedpiWave")
                val pos by transition.animateFloat(
                    initialValue = -1.5f,
                    targetValue = SIGNAL_SEGMENTS + 0.5f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1100, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "byedpiWavePos"
                )
                pos to highlight
            } else null

            Column(
                modifier = Modifier.size(22.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                repeat(SIGNAL_SEGMENTS) { rowIndex ->
                    val color = if (wave != null) {
                        val (pos, highlight) = wave
                        val intensity = (1f - abs(rowIndex - pos) / 1.6f).coerceIn(0f, 1f)
                        lerp(inactive, highlight, intensity)
                    } else {
                        val level = SIGNAL_SEGMENTS - rowIndex
                        if (level <= activeBars) fillColor else inactive
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(1.dp))
                            .background(color)
                    )
                }
            }
        }
    }
}
