package io.github.yulbax.frkn.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import org.jetbrains.skia.Image
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
        val icon = remember { BitmapPainter(Image.makeFromEncoded(appIconBytes()).toComposeImageBitmap()) }
        Window(
            onCloseRequest = {
                controller.shutdown()
                exitApplication()
            },
            title = "FRKN",
            icon = icon,
            state = rememberWindowState(width = 440.dp, height = 860.dp)
        ) {
            FRKNTheme { MainScreen() }
        }
    }
}

private fun appIconBytes(): ByteArray =
    checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(APP_ICON)) { "missing $APP_ICON" }
        .use { it.readBytes() }

private const val APP_ICON = "frkn-icon.png"

private fun autoConnect(settings: SettingsRepository, profiles: ProfileRepository, launcher: VpnLauncher) {
    val shouldConnect = runBlocking { settings.settings.first().autoConnect && profiles.selected.first() != null }
    if (shouldConnect) launcher.start()
}
