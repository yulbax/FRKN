package io.github.yulbax.frkn.ui.screens.frkn

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yulbax.frkn.R
import io.github.yulbax.frkn.util.formatRate
import io.github.yulbax.frkn.vpn.ConnectionStats
import io.github.yulbax.frkn.vpn.VpnState

@Composable
internal fun ConnectionCard(
    state: VpnState,
    stats: ConnectionStats,
    connected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val cycling = state is VpnState.CyclingFingerprint
    val busy = state == VpnState.Connecting || state == VpnState.Verifying || cycling
    val isError = state is VpnState.Error || state is VpnState.Reconnecting
    val vivid = isError || connected || busy

    val targetTop = when {
        isError -> Color(0xFFB3261E)
        busy -> Color(0xFFF9A825)
        connected -> Color(0xFF2E7D32)
        else -> scheme.surfaceVariant
    }
    val targetBottom = when {
        isError -> Color(0xFF8C1D18)
        busy -> Color(0xFFF57F17)
        connected -> Color(0xFF1B5E20)
        else -> scheme.surface
    }
    val top by animateColorAsState(targetTop, tween(500), label = "top")
    val bottom by animateColorAsState(targetBottom, tween(500), label = "bottom")
    val content by animateColorAsState(
        if (vivid) Color.White else scheme.onSurfaceVariant, tween(500), label = "content"
    )

    val title = when (state) {
        VpnState.Disconnected -> stringResource(R.string.vpn_disconnected)
        VpnState.Connecting -> stringResource(R.string.vpn_connecting)
        VpnState.Verifying -> stringResource(R.string.vpn_verifying)
        is VpnState.Connected -> stringResource(R.string.vpn_connected)
        is VpnState.Reconnecting -> stringResource(R.string.vpn_reconnecting)
        is VpnState.CyclingFingerprint -> stringResource(R.string.vpn_cycling_fingerprint)
        is VpnState.Error -> stringResource(R.string.vpn_not_connected)
    }
    val subtitle = when (state) {
        VpnState.Disconnected -> stringResource(R.string.vpn_tap_to_connect)
        VpnState.Connecting -> stringResource(R.string.vpn_establishing_tunnel)
        VpnState.Verifying -> stringResource(R.string.vpn_checking_connectivity)
        is VpnState.Connected -> stringResource(R.string.vpn_tap_to_disconnect)
        is VpnState.Reconnecting -> stringResource(R.string.vpn_attempt, state.attempt)
        is VpnState.CyclingFingerprint -> stringResource(R.string.vpn_cycling_fingerprint_sub, state.attempt)
        is VpnState.Error -> state.message
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .padding(horizontal = 24.dp, vertical = 30.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = content.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val showSpeed = connected && (stats.vpnUp || stats.byedpiUp)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (showSpeed) {
                        "↓ ${formatRate(stats.downlink)}   ↑ ${formatRate(stats.uplink)}"
                    } else " ",
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = if (showSpeed) 0.85f else 0f)
                )
            }

            Spacer(Modifier.width(16.dp))
            PowerToggle(busy = busy, vivid = vivid, enabled = enabled, content = content, onToggle = onToggle)
        }
    }
}

@Composable
private fun PowerToggle(
    busy: Boolean,
    vivid: Boolean,
    enabled: Boolean,
    content: Color,
    onToggle: () -> Unit
) {
    val circleColor = if (vivid) Color.White.copy(alpha = 0.2f) else Color.White
    val iconColor = if (vivid) content else Color(0xFF1C1B1F)
    Surface(
        onClick = onToggle,
        enabled = enabled,
        shape = CircleShape,
        color = circleColor,
        shadowElevation = if (vivid) 0.dp else 4.dp,
        modifier = Modifier
            .size(80.dp)
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (busy) {
                CircularProgressIndicator(
                    color = iconColor,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    contentDescription = stringResource(R.string.toggle_connection_cd),
                    tint = iconColor,
                    modifier = Modifier.size(38.dp)
                )
            }
        }
    }
}
