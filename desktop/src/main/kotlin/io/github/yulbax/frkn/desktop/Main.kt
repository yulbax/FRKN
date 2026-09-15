package io.github.yulbax.frkn.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.yulbax.frkn.data.SettingsRepository
import io.github.yulbax.frkn.data.profile.ProfileRepository
import io.github.yulbax.frkn.ui.di.uiModule
import io.github.yulbax.frkn.ui.screens.MainScreen
import io.github.yulbax.frkn.ui.theme.FRKNTheme
import io.github.yulbax.frkn.util.VpnLauncher
import io.github.yulbax.frkn.vpn.VpnController
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin

fun main() {
    if (DesktopPaths.isWindows && !WindowsElevation.isElevated() && WindowsElevation.relaunchElevated()) {
        exitProcess(0)
    }

    val koin = startKoin { modules(desktopModule, uiModule) }.koin
    val controller = koin.get<VpnController>()
    controller.launch()
    autoConnect(koin.get(), koin.get(), koin.get())

    application {
        Window(
            onCloseRequest = {
                controller.shutdown()
                exitApplication()
            },
            title = "FRKN",
            state = rememberWindowState(width = 440.dp, height = 860.dp)
        ) {
            FRKNTheme { MainScreen() }
        }
    }
}

private fun autoConnect(settings: SettingsRepository, profiles: ProfileRepository, launcher: VpnLauncher) {
    val shouldConnect = runBlocking { settings.settings.first().autoConnect && profiles.selected.first() != null }
    if (shouldConnect) launcher.start()
}
