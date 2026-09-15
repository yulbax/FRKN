package io.github.yulbax.frkn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.yulbax.frkn.ui.res.*
import io.github.yulbax.frkn.ui.platform.rememberSharer
import io.github.yulbax.frkn.util.DiagnosticsSource
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun Logs() {
    val diagnostics = koinInject<DiagnosticsSource>()
    val sharer = rememberSharer()
    val scope = rememberCoroutineScope()
    val shareLabel = stringResource(Res.string.export_logs)

    var reloadKey by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(reloadKey) {
        text = diagnostics.collect()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { reloadKey++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text(
                    stringResource(Res.string.logs_refresh),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        sharer.shareFile(
                            prefix = "frkn-log",
                            extension = "txt",
                            mimeType = "text/plain",
                            content = diagnostics.collect(),
                            title = shareLabel
                        )
                    }
                }
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text(stringResource(Res.string.share), modifier = Modifier.padding(start = 8.dp))
            }
        }

        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = text.ifBlank { stringResource(Res.string.logs_empty) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
