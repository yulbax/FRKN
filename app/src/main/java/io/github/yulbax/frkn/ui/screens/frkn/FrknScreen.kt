package io.github.yulbax.frkn.ui.screens.frkn

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import io.github.yulbax.frkn.R
import io.github.yulbax.frkn.data.ConnectionType
import io.github.yulbax.frkn.data.profile.ProfileEntity
import io.github.yulbax.frkn.ui.components.HintBanner
import io.github.yulbax.frkn.ui.components.groupRowShape
import io.github.yulbax.frkn.ui.viewmodel.ConnectionViewModel
import io.github.yulbax.frkn.ui.viewmodel.FrknUiState
import io.github.yulbax.frkn.ui.viewmodel.ProfileViewModel
import io.github.yulbax.frkn.ui.viewmodel.ServersUiState
import io.github.yulbax.frkn.vpn.ConnectionStats
import io.github.yulbax.frkn.vpn.VpnState
import org.koin.androidx.compose.koinViewModel
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun FrknScreen(
    onNavigateToApps: () -> Unit = {},
    viewModel: ConnectionViewModel = koinViewModel(),
    profileViewModel: ProfileViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val servers by profileViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProfileEntity?>(null) }
    var deleting by remember { mutableStateOf<ProfileEntity?>(null) }
    var showByeDpiDialog by remember { mutableStateOf(false) }
    var testingLatency by remember { mutableStateOf(false) }
    val byeDpiTest by viewModel.byeDpiTest.collectAsStateWithLifecycle()

    LaunchedEffect(servers.delays) {
        if (testingLatency) testingLatency = false
    }
    LaunchedEffect(testingLatency) {
        if (testingLatency) {
            delay(10000.milliseconds)
            testingLatency = false
        }
    }

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
        VpnState.Verifying, is VpnState.Connected -> true
        else -> false
    }

    val content = FrknScreenContent(
        ui = ui,
        state = state,
        stats = stats,
        servers = servers,
        connected = connected,
        onToggle = { if (connected) viewModel.stopVpn() else onConnectClick() },
        onDismissHint = { viewModel.dismissHomeHint() },
        onNavigateToApps = onNavigateToApps,
        onByedpiClick = { showByeDpiDialog = true },
        onAddServerClick = { showAddDialog = true },
        onTestClick = {
            if (!testingLatency) {
                profileViewModel.testAll()
                testingLatency = true
            }
        },
        onSelect = { profileViewModel.select(it) },
        onEdit = { editing = it },
        onShare = { profile ->
            shareServerLink(context, profile.subscriptionUrl.ifEmpty { profile.link })
        },
        onRefresh = { profileViewModel.refreshSubscription(it) },
        onDelete = { deleting = it }
    )

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (landscape) FrknScreenLandscape(content) else FrknScreenPortrait(content)

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

    if (showAddDialog) {
        AddServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { profileViewModel.add(it); showAddDialog = false }
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

    deleting?.let { profile ->
        val displayName = profile.name.ifEmpty { stringResource(R.string.unnamed_profile) }
        AlertDialog(
            onDismissRequest = { deleting = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        profileViewModel.delete(profile)
                        deleting = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
            title = { Text(stringResource(R.string.delete_server_title)) },
            text = { Text(stringResource(R.string.delete_server_message, displayName)) }
        )
    }

    servers.error?.let { message ->
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

private class FrknScreenContent(
    val ui: FrknUiState,
    val state: VpnState,
    val stats: ConnectionStats,
    val servers: ServersUiState,
    val connected: Boolean,
    val onToggle: () -> Unit,
    val onDismissHint: () -> Unit,
    val onNavigateToApps: () -> Unit,
    val onByedpiClick: () -> Unit,
    val onAddServerClick: () -> Unit,
    val onTestClick: () -> Unit,
    val onSelect: (ProfileEntity) -> Unit,
    val onEdit: (ProfileEntity) -> Unit,
    val onShare: (ProfileEntity) -> Unit,
    val onRefresh: (ProfileEntity) -> Unit,
    val onDelete: (ProfileEntity) -> Unit
)

@Composable
private fun FrknScreenPortrait(c: FrknScreenContent) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        ConnectionSection(c)

        Spacer(Modifier.height(24.dp))
        ServerListSection(
            c = c,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        if (c.servers.profiles.isNotEmpty()) Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FrknScreenLandscape(c: FrknScreenContent) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ConnectionSection(c, landscape = true)
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            ServerListSection(
                c = c,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                showHeader = false
            )
        }
    }
}

@Composable
private fun ColumnScope.ConnectionSection(c: FrknScreenContent, landscape: Boolean = false) {
    if (!c.ui.homeHintSeen) {
        HintBanner(
            text = stringResource(R.string.home_hint),
            actionLabel = stringResource(R.string.home_hint_action),
            onAction = {
                c.onDismissHint()
                c.onNavigateToApps()
            },
            onDismiss = c.onDismissHint
        )
        Spacer(Modifier.height(12.dp))
    }

    val enabled = c.state != VpnState.Connecting &&
        (c.connected || (c.servers.selected != null && c.ui.hasRoutedApps))

    if (landscape) {
        ConnectionCard(
            state = c.state,
            stats = c.stats,
            connected = c.connected,
            enabled = enabled,
            onToggle = c.onToggle,
            modifier = Modifier.weight(2f).fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))
        ChannelsRow(c, modifier = Modifier.weight(1f).fillMaxWidth(), fillHeight = true)
    } else {
        ConnectionCard(
            state = c.state,
            stats = c.stats,
            connected = c.connected,
            enabled = enabled,
            onToggle = c.onToggle
        )

        Spacer(Modifier.height(20.dp))
        ChannelsRow(c)
    }

    if (!c.connected && !c.ui.hasRoutedApps) {
        Spacer(Modifier.height(12.dp))
        AddAppsCard(onClick = c.onNavigateToApps)
    }
}

@Composable
private fun ChannelsRow(c: FrknScreenContent, modifier: Modifier = Modifier, fillHeight: Boolean = false) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val cardModifier = if (fillHeight) Modifier.weight(1f).fillMaxHeight() else Modifier.weight(1f)
        ChannelStatusCard(
            type = ConnectionType.VPN,
            active = c.connected,
            up = c.stats.vpnUp,
            hasApps = c.ui.hasVpnApps,
            cycling = c.stats.vpnCycling,
            latencyMs = c.stats.vpnLatencyMs,
            country = c.stats.vpnCountry,
            shape = groupRowShape(0, 2),
            modifier = cardModifier,
            fillHeight = fillHeight
        )
        ChannelStatusCard(
            type = ConnectionType.BYEDPI,
            active = c.stats.byedpiActive,
            up = c.stats.byedpiUp,
            hasApps = c.ui.hasByedpiApps,
            latencyMs = c.stats.byedpiLatencyMs,
            shape = groupRowShape(1, 2),
            modifier = cardModifier,
            fillHeight = fillHeight,
            reachable = c.stats.byedpiReachable,
            total = c.stats.byedpiTotal,
            checking = c.stats.byedpiChecking,
            onClick = if (c.stats.byedpiActive && c.stats.byedpiUp) c.onByedpiClick else null
        )
    }
}

@Composable
private fun ServerListSection(c: FrknScreenContent, modifier: Modifier, showHeader: Boolean = true) {
    if (showHeader) {
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
            TextButton(
                onClick = c.onAddServerClick,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.add_server))
            }
        }
    }

    if (c.servers.profiles.isEmpty()) {
        if (showHeader) {
            Text(
                text = stringResource(R.string.servers_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.servers_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AddServerFab(
                    onClick = c.onAddServerClick,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                )
            }
        }
        return
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
    ) {
        val showTest = c.connected && c.servers.profiles.size > 1
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 6.dp,
                    end = 6.dp,
                    top = 6.dp,
                    bottom = if (showTest || !showHeader) 64.dp else 6.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val fastestId = c.servers.delays
                    .filterValues { it > 0 }
                    .minByOrNull { it.value }
                    ?.key
                items(c.servers.profiles, key = { it.id }) { profile ->
                    ServerRow(
                        profile = profile,
                        isSelected = profile.id == c.servers.selected?.id,
                        delayMs = c.servers.delays[profile.id],
                        isBest = profile.id == fastestId,
                        onSelect = { c.onSelect(profile) },
                        onEdit = { c.onEdit(profile) },
                        onShare = { c.onShare(profile) },
                        onRefresh = { c.onRefresh(profile) },
                        onDelete = { c.onDelete(profile) }
                    )
                }
            }
            AnimatedSpeedometerButton(visible = showTest, onClick = c.onTestClick)
            if (!showHeader) {
                AddServerFab(
                    onClick = c.onAddServerClick,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun AddAppsCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = stringResource(R.string.connection_no_routed_apps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun BoxScope.AnimatedSpeedometerButton(visible: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it * 2 } + fadeIn(),
        exit = slideOutVertically { it * 2 } + fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 12.dp)
    ) {
        SpeedometerButton(onClick = onClick)
    }
}

@Composable
private fun SpeedometerButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Speed,
                contentDescription = stringResource(R.string.server_test_latency),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun AddServerFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.add_server),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(24.dp)
            )
        }
    }
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
