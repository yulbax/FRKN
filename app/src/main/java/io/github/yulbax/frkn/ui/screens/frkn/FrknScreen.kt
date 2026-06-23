package io.github.yulbax.frkn.ui.screens.frkn

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import io.github.yulbax.frkn.R
import io.github.yulbax.frkn.data.ConnectionType
import io.github.yulbax.frkn.data.profile.ProfileEntity
import io.github.yulbax.frkn.ui.components.HintBanner
import io.github.yulbax.frkn.ui.components.groupRowShape
import io.github.yulbax.frkn.ui.viewmodel.ConnectionViewModel
import io.github.yulbax.frkn.ui.viewmodel.ProfileViewModel
import io.github.yulbax.frkn.vpn.VpnState
import org.koin.androidx.compose.koinViewModel
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun FrknScreen(
    onNavigateToApps: () -> Unit = {},
    viewModel: ConnectionViewModel = koinViewModel(),
    profileViewModel: ProfileViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val ui by viewModel.uiState.collectAsState()
    val servers by profileViewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProfileEntity?>(null) }
    var showByeDpiDialog by remember { mutableStateOf(false) }
    var testingLatency by remember { mutableStateOf(false) }
    val byeDpiTest by viewModel.byeDpiTest.collectAsState()

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))

        if (!ui.homeHintSeen) {
            HintBanner(
                text = stringResource(R.string.home_hint),
                actionLabel = stringResource(R.string.home_hint_action),
                onAction = {
                    viewModel.dismissHomeHint()
                    onNavigateToApps()
                },
                onDismiss = { viewModel.dismissHomeHint() }
            )
            Spacer(Modifier.height(12.dp))
        }

        ConnectionCard(
            state = state,
            stats = stats,
            connected = connected,
            enabled = state != VpnState.Connecting &&
                (connected || (servers.selected != null && ui.hasRoutedApps)),
            onToggle = { if (connected) viewModel.stopVpn() else onConnectClick() }
        )

        if (!connected && !ui.hasRoutedApps) {
            Spacer(Modifier.height(12.dp))
            HintBanner(
                text = stringResource(R.string.connection_no_routed_apps),
                actionLabel = stringResource(R.string.home_hint_action),
                onAction = onNavigateToApps
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
                hasApps = ui.hasVpnApps,
                cycling = stats.vpnCycling,
                latencyMs = stats.vpnLatencyMs,
                country = stats.vpnCountry,
                shape = groupRowShape(0, 2),
                modifier = Modifier.weight(1f)
            )
            ChannelStatusCard(
                type = ConnectionType.BYEDPI,
                active = stats.byedpiActive,
                up = stats.byedpiUp,
                hasApps = ui.hasByedpiApps,
                latencyMs = stats.byedpiLatencyMs,
                country = stats.byedpiCountry,
                shape = groupRowShape(1, 2),
                modifier = Modifier.weight(1f),
                reachable = stats.byedpiReachable,
                total = stats.byedpiTotal,
                checking = stats.byedpiChecking,
                onClick = if (stats.byedpiActive && stats.byedpiUp) {
                    { showByeDpiDialog = true }
                } else null
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
            TextButton(
                onClick = { showAddDialog = true },
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

        if (servers.profiles.isEmpty()) {
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
                val showTest = connected && servers.profiles.size > 1
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 6.dp,
                            end = 6.dp,
                            top = 6.dp,
                            bottom = if (showTest) 64.dp else 6.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val fastestId = servers.delays
                            .filterValues { it > 0 }
                            .minByOrNull { it.value }
                            ?.key
                        items(servers.profiles, key = { it.id }) { profile ->
                            ServerRow(
                                profile = profile,
                                isSelected = profile.id == servers.selected?.id,
                                delayMs = servers.delays[profile.id],
                                isBest = profile.id == fastestId,
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
                    AnimatedSpeedometerButton(
                        visible = showTest,
                        onClick = {
                            if (!testingLatency) {
                                profileViewModel.testAll()
                                testingLatency = true
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
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
        modifier = modifier.size(44.dp)
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
