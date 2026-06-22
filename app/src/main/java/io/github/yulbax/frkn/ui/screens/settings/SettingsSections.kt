package io.github.yulbax.frkn.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.yulbax.frkn.R
import io.github.yulbax.frkn.data.AppConfigBackup
import io.github.yulbax.frkn.ui.components.ClickableRow
import io.github.yulbax.frkn.ui.components.CommittedTextField
import io.github.yulbax.frkn.ui.components.DropdownSetting
import io.github.yulbax.frkn.ui.components.GroupCard
import io.github.yulbax.frkn.ui.components.SwitchRow
import io.github.yulbax.frkn.ui.components.transparentFieldColors
import io.github.yulbax.frkn.ui.viewmodel.SettingsUiState
import io.github.yulbax.frkn.ui.viewmodel.SettingsViewModel
import io.github.yulbax.frkn.util.Diagnostics
import io.github.yulbax.frkn.vpn.core.Ipv6Mode
import io.github.yulbax.frkn.vpn.core.TlsFingerprint
import io.github.yulbax.frkn.vpn.core.TunStack

@Composable
internal fun ByeDpiSection(ui: SettingsUiState, viewModel: SettingsViewModel) {
    val savedByeDpiArgs = ui.byeDpiArgs
    var text by remember { mutableStateOf(savedByeDpiArgs) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(savedByeDpiArgs) { if (!focused) text = savedByeDpiArgs }

    GroupCard(
        title = stringResource(R.string.byedpi_cli_args),
        action = {
            Text(
                text = stringResource(R.string.reset_to_default),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    text = ""
                    viewModel.setByeDpiArgs("")
                }
            )
        },
        items = listOf {
            TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = {
                    Text(viewModel.byeDpiArgsDefault, fontFamily = FontFamily.Monospace)
                },
                label = { Text(stringResource(R.string.flags_label)) },
                colors = transparentFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { st ->
                        if (focused && !st.isFocused) viewModel.setByeDpiArgs(text.trim())
                        focused = st.isFocused
                    }
            )
        }
    )
}

@Composable
internal fun AutostartSection(ui: SettingsUiState, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val autoConnect = ui.autoConnect

    GroupCard(
        title = stringResource(R.string.autostart_title),
        items = listOf(
            {
                SwitchRow(stringResource(R.string.auto_connect), autoConnect) {
                    viewModel.setAutoConnect(it)
                }
            },
            {
                ClickableRow(label = stringResource(R.string.always_on_vpn)) {
                    runCatching {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_VPN_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        )
    )
}

@Composable
internal fun NetworkSection(ui: SettingsUiState, viewModel: SettingsViewModel) {
    val tunStack = ui.tunStack
    val mtu = ui.mtu
    val ipv6Mode = ui.ipv6Mode
    val dnsRemote = ui.dnsRemote
    val dnsDirect = ui.dnsDirect
    val sniff = ui.sniff
    val bypassLan = ui.bypassLan
    val preferredFingerprint = ui.preferredFingerprint
    val fromConfigLabel = stringResource(R.string.fingerprint_from_config)

    GroupCard(
        title = stringResource(R.string.network_title),
        items = listOf(
            {
                DropdownSetting(
                    label = stringResource(R.string.tls_fingerprint),
                    options = listOf(fromConfigLabel) + TlsFingerprint.entries.map { it.wire },
                    selected = preferredFingerprint?.wire ?: fromConfigLabel,
                    onSelect = { viewModel.setPreferredFingerprint(TlsFingerprint.fromWire(it)) }
                )
            },
            {
                DropdownSetting(
                    label = stringResource(R.string.tun_stack),
                    options = TunStack.entries.map { it.wire },
                    selected = tunStack.wire,
                    onSelect = { viewModel.setTunStack(TunStack.fromWire(it)) }
                )
            },
            {
                CommittedTextField(
                    label = stringResource(R.string.mtu),
                    saved = mtu.toString(),
                    keyboardType = KeyboardType.Number,
                    onCommit = { it.trim().toIntOrNull()?.coerceIn(1280, 9000)?.let(viewModel::setMtu) }
                )
            },
            {
                DropdownSetting(
                    label = stringResource(R.string.ipv6),
                    options = Ipv6Mode.entries.map { it.wire },
                    selected = ipv6Mode.wire,
                    onSelect = { viewModel.setIpv6Mode(Ipv6Mode.fromWire(it)) }
                )
            },
            {
                CommittedTextField(
                    label = stringResource(R.string.remote_dns),
                    saved = dnsRemote,
                    onCommit = { if (it.isNotBlank()) viewModel.setDnsRemote(it) }
                )
            },
            {
                CommittedTextField(
                    label = stringResource(R.string.direct_dns),
                    saved = dnsDirect,
                    onCommit = { if (it.isNotBlank()) viewModel.setDnsDirect(it) }
                )
            },
            {
                SwitchRow(stringResource(R.string.sniff_destination), sniff) { viewModel.setSniff(it) }
            },
            {
                SwitchRow(stringResource(R.string.bypass_lan), bypassLan) { viewModel.setBypassLan(it) }
            }
        )
    )
}

@Composable
internal fun BackupSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val exportAppConfigLabel = stringResource(R.string.export_app_config)
    val importFailedFormat = stringResource(R.string.import_failed)
    val importDoneFormat = stringResource(R.string.import_done)
    val invalidFileMessage = stringResource(R.string.import_invalid_file)

    var showExportDialog by remember { mutableStateOf(false) }
    var importPreview by remember { mutableStateOf<AppConfigBackup?>(null) }

    fun share(uri: Uri, mimeType: String, chooserTitle: String) {
        runCatching {
            context.startActivity(
                Intent.createChooser(Diagnostics.shareIntent(uri, mimeType), chooserTitle)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.prepareImport(uri) { backup ->
                if (backup == null) {
                    Toast.makeText(context, invalidFileMessage, Toast.LENGTH_LONG).show()
                } else {
                    importPreview = backup
                }
            }
        }
    }

    GroupCard(
        title = stringResource(R.string.backup_title),
        items = listOf(
            {
                ClickableRow(
                    label = stringResource(R.string.export_app_config),
                    trailingIcon = Icons.Filled.Upload
                ) {
                    showExportDialog = true
                }
            },
            {
                ClickableRow(
                    label = stringResource(R.string.import_app_config),
                    trailingIcon = Icons.Filled.Download
                ) {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            }
        )
    )

    if (showExportDialog) {
        BackupSelectionDialog(
            title = stringResource(R.string.backup_export_title),
            confirmLabel = stringResource(R.string.export),
            settingsAvailable = true,
            appsAvailable = true,
            profilesAvailable = true,
            onConfirm = { selection ->
                showExportDialog = false
                viewModel.exportConfig(selection) { uri ->
                    share(uri, "application/json", exportAppConfigLabel)
                }
            },
            onDismiss = { showExportDialog = false }
        )
    }

    importPreview?.let { backup ->
        BackupSelectionDialog(
            title = stringResource(R.string.backup_import_title),
            confirmLabel = stringResource(R.string.ok),
            settingsAvailable = backup.settings != null,
            appsAvailable = backup.apps != null,
            profilesAvailable = backup.profiles != null,
            onConfirm = { selection ->
                importPreview = null
                viewModel.applyImport(backup, selection) { result ->
                    val message = if (result.error != null) {
                        importFailedFormat.format(result.error)
                    } else {
                        importDoneFormat.format(result.applied, result.skipped, result.profilesAdded)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { importPreview = null }
        )
    }
}

@Composable
private fun BackupSelectionDialog(
    title: String,
    confirmLabel: String,
    settingsAvailable: Boolean,
    appsAvailable: Boolean,
    profilesAvailable: Boolean,
    onConfirm: (Diagnostics.BackupSelection) -> Unit,
    onDismiss: () -> Unit
) {
    var settings by remember { mutableStateOf(settingsAvailable) }
    var apps by remember { mutableStateOf(appsAvailable) }
    var profiles by remember { mutableStateOf(profilesAvailable) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (settingsAvailable) {
                    CheckRow(stringResource(R.string.settings), settings) { settings = it }
                }
                if (appsAvailable) {
                    CheckRow(stringResource(R.string.applications), apps) { apps = it }
                }
                if (profilesAvailable) {
                    CheckRow(stringResource(R.string.servers_title), profiles) { profiles = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = settings || apps || profiles,
                onClick = { onConfirm(Diagnostics.BackupSelection(settings, apps, profiles)) }
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label)
    }
}
