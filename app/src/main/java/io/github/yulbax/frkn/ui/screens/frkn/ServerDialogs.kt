package io.github.yulbax.frkn.ui.screens.frkn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yulbax.frkn.R
import io.github.yulbax.frkn.data.profile.ProfileEntity

@Composable
internal fun EditServerDialog(
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
internal fun AddServerDialog(
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
