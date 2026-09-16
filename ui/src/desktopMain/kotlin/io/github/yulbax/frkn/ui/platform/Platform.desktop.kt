package io.github.yulbax.frkn.ui.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberShowMessage(): (String) -> Unit = LocalMessages.current

@Composable
actual fun rememberVpnStartRequest(onGranted: () -> Unit): () -> Unit = onGranted

@Composable
actual fun rememberSharer(): Sharer = remember { DesktopSharer() }

private class DesktopSharer : Sharer {
    override fun shareText(text: String, title: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override suspend fun shareFile(prefix: String, extension: String, mimeType: String, content: String, title: String) {
        val target = chooseFile(title, FileDialog.SAVE, "$prefix.$extension") ?: return
        withContext(Dispatchers.IO) { target.writeText(content) }
    }
}

@Composable
actual fun rememberFilePicker(mimeTypes: List<String>, onPicked: (PickedFile) -> Unit): () -> Unit = {
    chooseFile(title = "", mode = FileDialog.LOAD, suggestedName = null)?.let { file ->
        onPicked { file.inputStream() }
    }
}

@Composable
actual fun rememberSystemVpnSettings(): (() -> Unit)? = null

@Composable
actual fun rememberAppIcon(appId: String): ImageBitmap? = null

@Composable
actual fun platformColorScheme(darkTheme: Boolean): ColorScheme? = null

actual object AppLocale {
    private var selected: String? = null

    actual fun currentTag(): String? = selected

    actual fun apply(tag: String?) {
        selected = tag
        Locale.setDefault(tag?.let(Locale::forLanguageTag) ?: systemLocale)
    }

    private val systemLocale: Locale = Locale.getDefault()
}

private fun chooseFile(title: String, mode: Int, suggestedName: String?): File? {
    val dialog = FileDialog(null as Frame?, title, mode)
    suggestedName?.let { dialog.file = it }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}
