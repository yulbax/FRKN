package io.github.yulbax.frkn.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yulbax.frkn.R
import io.github.yulbax.frkn.ui.components.GroupCard
import io.github.yulbax.frkn.ui.components.SwitchRow
import io.github.yulbax.frkn.ui.viewmodel.SettingsViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun Settings(
    viewModel: SettingsViewModel = koinViewModel()
) {
    val ui by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        GroupCard(
            title = stringResource(R.string.language),
            items = listOf { LanguagePicker() }
        )

        GroupCard(
            title = stringResource(R.string.applications),
            items = listOf {
                SwitchRow(stringResource(R.string.show_system_apps), ui.showSystemApps) {
                    viewModel.toggleShowSystemApps()
                }
            }
        )

        ByeDpiSection(ui, viewModel)
        AutostartSection(ui, viewModel)
        NetworkSection(ui, viewModel)
        BackupSection(viewModel)
    }
}
