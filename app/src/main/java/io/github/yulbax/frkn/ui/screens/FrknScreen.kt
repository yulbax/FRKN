package io.github.yulbax.frkn.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yulbax.frkn.R
import io.github.yulbax.frkn.data.ConnectionType
import io.github.yulbax.frkn.data.profile.ProfileEntity
import io.github.yulbax.frkn.ui.components.ConnectionTypeIcon
import io.github.yulbax.frkn.ui.components.CountryFlag
import io.github.yulbax.frkn.ui.components.groupRowShape
import io.github.yulbax.frkn.ui.viewmodel.ConnectionViewModel
import io.github.yulbax.frkn.ui.viewmodel.ProfileViewModel
import io.github.yulbax.frkn.util.formatRate
import io.github.yulbax.frkn.vpn.ConnectionStats
import io.github.yulbax.frkn.vpn.VpnState
import org.koin.androidx.compose.koinViewModel
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.ceil

@Composable
fun Connection(
    viewModel: ConnectionViewModel = koinViewModel(),
    profileViewModel: ProfileViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val hasRoutedApps by viewModel.hasRoutedApps.collectAsState()
    val profiles by profileViewModel.profiles.collectAsState()
    val selected by profileViewModel.selected.collectAsState()
    val error by profileViewModel.error.collectAsState()
    val context = LocalContext.current

    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProfileEntity?>(null) }
    var showByeDpiDialog by remember { mutableStateOf(false) }
    val byeDpiTest by viewModel.byeDpiTest.collectAsState()

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.startVpn()
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestConsentThenStart(context, consentLauncher::launch, viewModel::startVpn)
    }

    fun onConnectClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestConsentThenStart(context, consentLauncher::launch, viewModel::startVpn)
        }
    }

    val connected = when (state) {
        VpnState.Verifying, is VpnState.Connected, is VpnState.Reconnecting -> true
        else -> false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))

        ConnectionCard(
            state = state,
            stats = stats,
            connected = connected,
            enabled = state != VpnState.Connecting &&
                (connected || (selected != null && hasRoutedApps)),
            onToggle = { if (connected) viewModel.stopVpn() else onConnectClick() }
        )

        if (!connected && !hasRoutedApps) {
            Text(
                text = stringResource(R.string.connection_no_routed_apps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ChannelStatusCard(
                type = ConnectionType.VPN,
                active = connected,
                up = stats.vpnUp,
                latencyMs = stats.vpnLatencyMs,
                country = stats.vpnCountry,
                shape = groupRowShape(0, 2),
                modifier = Modifier.weight(1f)
            )
            ChannelStatusCard(
                type = ConnectionType.BYEDPI,
                active = stats.byedpiActive,
                up = stats.byedpiUp,
                latencyMs = stats.byedpiLatencyMs,
                country = stats.byedpiCountry,
                shape = groupRowShape(1, 2),
                modifier = Modifier.weight(1f),
                reachable = stats.byedpiReachable,
                total = stats.byedpiTotal,
                checking = stats.byedpiChecking,
                onClick = { showByeDpiDialog = true }
            )
        }

        if (showByeDpiDialog) {
            LaunchedEffect(Unit) { viewModel.runByeDpiTest(full = false) }
            ByeDpiTestDialog(
                test = byeDpiTest,
                onRunFull = { viewModel.runByeDpiTest(full = true) },
                onDismiss = {
                    viewModel.stopByeDpiTest()
                    showByeDpiDialog = false
                }
            )
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.servers_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp)
            )
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_server_cd))
            }
        }

        if (profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.servers_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        ServerRow(
                            profile = profile,
                            isSelected = profile.id == selected?.id,
                            onSelect = { profileViewModel.select(profile) },
                            onEdit = { editing = profile },
                            onShare = {
                                val shareLink = profile.subscriptionUrl.ifEmpty { profile.link }
                                shareServerLink(context, shareLink)
                            },
                            onRefresh = { profileViewModel.refreshSubscription(profile) },
                            onDelete = { profileViewModel.delete(profile) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showAddDialog) {
        AddServerDialog(
            onDismiss = { showAddDialog = false },
            onAddLink = { profileViewModel.addLink(it); showAddDialog = false },
            onImportSubscription = {
                profileViewModel.importSubscription(it); showAddDialog = false
            }
        )
    }

    editing?.let { profile ->
        EditServerDialog(
            profile = profile,
            onDismiss = { editing = null },
            onSave = { name, link ->
                profileViewModel.update(profile, name, link)
                editing = null
            }
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { profileViewModel.clearError() },
            confirmButton = {
                TextButton(onClick = { profileViewModel.clearError() }) { Text(stringResource(R.string.ok)) }
            },
            title = { Text(stringResource(R.string.dialog_error)) },
            text = { Text(message) }
        )
    }
}

@Composable
private fun ConnectionCard(
    state: VpnState,
    stats: ConnectionStats,
    connected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val busy = state == VpnState.Connecting || state == VpnState.Verifying
    val isError = state is VpnState.Error || state is VpnState.Reconnecting
    val vivid = isError || connected || busy

    val targetTop = when {
        isError -> Color(0xFFB3261E)
        connected -> Color(0xFF2E7D32)
        busy -> Color(0xFFF9A825)
        else -> scheme.surfaceVariant
    }
    val targetBottom = when {
        isError -> Color(0xFF8C1D18)
        connected -> Color(0xFF1B5E20)
        busy -> Color(0xFFF57F17)
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
        is VpnState.Error -> stringResource(R.string.vpn_not_connected)
    }
    val subtitle = when (state) {
        VpnState.Disconnected -> stringResource(R.string.vpn_tap_to_connect)
        VpnState.Connecting -> stringResource(R.string.vpn_establishing_tunnel)
        VpnState.Verifying -> stringResource(R.string.vpn_checking_connectivity)
        is VpnState.Connected -> stringResource(R.string.vpn_tap_to_disconnect)
        is VpnState.Reconnecting -> stringResource(R.string.vpn_attempt, state.attempt)
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
                Text(title, style = MaterialTheme.typography.headlineMedium, color = content)
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
    val scheme = MaterialTheme.colorScheme
    val circleColor = if (vivid) Color.White.copy(alpha = 0.2f) else scheme.primary
    val iconColor = if (vivid) content else scheme.onPrimary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .background(circleColor)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onToggle)
    ) {
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

@Composable
private fun ChannelStatusCard(
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

@Composable
private fun ServerRow(
    profile: ProfileEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val splitColors = ButtonColors(
        containerColor = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
        disabledContentColor = MaterialTheme.colorScheme.surface,
        disabledContainerColor = MaterialTheme.colorScheme.onSurface
    )

    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(18.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = isSelected, onClick = onSelect)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    profile.name.ifEmpty { stringResource(R.string.unnamed_profile) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.type,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (profile.subscriptionUrl.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.secondary
                        ) {
                            Text(
                                stringResource(R.string.subscription),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            SplitButtonLayout(
                leadingButton = {
                    SplitButtonDefaults.LeadingButton(
                        onClick = onEdit,
                        colors = splitColors
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                            contentDescription = stringResource(R.string.edit),
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.edit))
                    }
                },
                trailingButton = {
                    Box {
                        SplitButtonDefaults.TrailingButton(
                            checked = menuExpanded,
                            onCheckedChange = { menuExpanded = it },
                            colors = splitColors
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.more_options_cd)
                            )
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share)) },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = {
                                    onShare()
                                    menuExpanded = false
                                }
                            )
                            if (profile.subscriptionUrl.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.refresh_subscription)) },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    onClick = {
                                        onRefresh()
                                        menuExpanded = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    onDelete()
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun EditServerDialog(
    profile: ProfileEntity,
    onDismiss: () -> Unit,
    onSave: (name: String, link: String) -> Unit
) {
    var name by remember { mutableStateOf(profile.name) }
    var link by remember { mutableStateOf(profile.link) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_server)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text(stringResource(R.string.share_link_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = { onSave(name, link) }) { Text(stringResource(R.string.save)) }
            }
        }
    )
}

@Composable
private fun AddServerDialog(
    onDismiss: () -> Unit,
    onAddLink: (String) -> Unit,
    onImportSubscription: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_server)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.add_server_instructions),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.link_or_subscription_url)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onAddLink(text) },
                enabled = text.isNotBlank()
            ) { Text(stringResource(R.string.add_link)) }
        },
        dismissButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onImportSubscription(text) },
                enabled = text.isNotBlank()
            ) { Text(stringResource(R.string.import_subscription)) }
        }
    )
}

private fun requestConsentThenStart(
    context: Context,
    launchConsent: (Intent) -> Unit,
    startVpn: () -> Unit
) {
    val prepareIntent = VpnService.prepare(context)
    if (prepareIntent != null) launchConsent(prepareIntent) else startVpn()
}

private fun shareServerLink(context: Context, link: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, link)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_server_chooser)))
}