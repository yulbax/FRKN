package io.github.yulbax.frkn.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import io.github.yulbax.frkn.data.App
import io.github.yulbax.frkn.data.AppConfigBackup
import io.github.yulbax.frkn.data.AppDao
import io.github.yulbax.frkn.data.ConnectionType
import io.github.yulbax.frkn.data.SettingsDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Diagnostics {

    private const val MAX_BOX_LOG_BYTES = 1024 * 1024L

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    
    private fun tail(file: File, maxBytes: Long): String {
        val length = file.length()
        if (length <= maxBytes) return file.readText()
        return file.inputStream().use { stream ->
            stream.skip(length - maxBytes)
            "(truncated, showing last ${maxBytes / 1024} KB of ${length / 1024} KB)\n" +
                stream.readBytes().decodeToString()
        }
    }

    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    private fun shareDir(context: Context): File =
        File(context.cacheDir, "share").apply { mkdirs() }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    suspend fun collectDiagnostics(context: Context, frknLog: FrknLog): String =
        withContext(Dispatchers.IO) {
            val boxLog = File(File(context.filesDir, "work"), "box.log")
            buildString {
                append("=== FRKN diagnostics ===\n")
                append("time: ").append(Date()).append('\n')
                append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append("android: ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                append("abi: ").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
                append("\n=== app log (frkn.log) ===\n")
                append(frknLog.dump())
                append("\n=== sing-box (box.log) ===\n")
                append(if (boxLog.exists()) tail(boxLog, MAX_BOX_LOG_BYTES) else "(no box.log)")
            }
        }

    
    suspend fun exportLogs(context: Context, frknLog: FrknLog): Uri = withContext(Dispatchers.IO) {
        val out = File(shareDir(context), "frkn-log-${stamp.format(Date())}.txt")
        out.writeText(collectDiagnostics(context, frknLog))
        uriFor(context, out)
    }

    
    suspend fun exportAppConfig(context: Context, appDao: AppDao, settingsDao: SettingsDao): Uri =
        withContext(Dispatchers.IO) {
            val apps = appDao.getAllApps().first()
            val backup = AppConfigBackup(
                version = AppConfigBackup.CURRENT_VERSION,
                apps = apps.associate { it.packageName to it.connectionType.name },
                settings = settingsDao.observeSettings().first()
            )
            val out = File(shareDir(context), "frkn-config-${stamp.format(Date())}.json")
            out.writeText(json.encodeToString(AppConfigBackup.serializer(), backup))
            uriFor(context, out)
        }

    data class ImportResult(
        val applied: Int,
        val skipped: Int,
        val settingsApplied: Boolean = false,
        val error: String? = null
    )


    suspend fun importAppConfig(context: Context, appDao: AppDao, settingsDao: SettingsDao, uri: Uri): ImportResult =
        withContext(Dispatchers.IO) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull() ?: return@withContext ImportResult(0, 0, error = "Could not read file")

            val backup = runCatching { json.decodeFromString(AppConfigBackup.serializer(), text) }
                .getOrElse { return@withContext ImportResult(0, 0, error = "Invalid config file") }

            var applied = 0
            var skipped = 0
            val updates = mutableListOf<App>()
            for ((pkg, typeName) in backup.apps) {
                val type = runCatching { ConnectionType.valueOf(typeName) }.getOrNull()
                val app = if (type != null) appDao.getApp(pkg) else null
                if (type == null || app == null) {
                    skipped++
                    continue
                }
                if (app.connectionType != type) updates.add(app.copy(connectionType = type))
                applied++
            }
            if (updates.isNotEmpty()) appDao.upsertApps(updates)

            val settings = backup.settings
            if (settings != null) settingsDao.upsertSettings(settings.copy(id = 1))
            ImportResult(applied, skipped, settingsApplied = settings != null)
        }

    fun shareIntent(uri: Uri, mimeType: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
