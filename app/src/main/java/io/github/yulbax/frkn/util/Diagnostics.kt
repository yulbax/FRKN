package io.github.yulbax.frkn.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import io.github.yulbax.frkn.data.App
import io.github.yulbax.frkn.data.AppConfigBackup
import io.github.yulbax.frkn.data.AppDatabase
import io.github.yulbax.frkn.data.BackupProfile
import io.github.yulbax.frkn.data.BackupSettings
import io.github.yulbax.frkn.data.ConnectionType
import io.github.yulbax.frkn.data.SettingsEntity
import io.github.yulbax.frkn.data.toBackupProfile
import io.github.yulbax.frkn.data.toBackupSettings
import io.github.yulbax.frkn.data.toEntity
import io.github.yulbax.frkn.vpn.core.Ipv6Mode
import io.github.yulbax.frkn.vpn.core.TlsFingerprint
import io.github.yulbax.frkn.vpn.core.TunStack
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object Diagnostics {
    private const val MAX_BOX_LOG_BYTES = 1024 * 1024L
    private const val MAX_PACKAGE_NAME_LENGTH = 255
    private const val MAX_PROFILE_NAME_LENGTH = 512
    private const val MAX_LINK_LENGTH = 16 * 1024
    private const val MAX_OUTBOUND_LENGTH = 256 * 1024
    private const val MAX_BYEDPI_ARGS_LENGTH = 16 * 1024
    private const val MAX_DNS_LENGTH = 255
    private const val SHARE_FILE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

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

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

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
                append(redactSensitiveData(frknLog.dump()))
                append("\n=== sing-box (box.log) ===\n")
                append(
                    if (boxLog.exists()) {
                        redactSensitiveData(tail(boxLog, MAX_BOX_LOG_BYTES))
                    } else {
                        "(no box.log)"
                    }
                )
            }
        }

    suspend fun exportLogs(context: Context, frknLog: FrknLog): Uri = withContext(Dispatchers.IO) {
        val out = freshShareFile(context, prefix = "frkn-log", extension = "txt")
        out.writeText(collectDiagnostics(context, frknLog))
        uriFor(context, out)
    }

    data class BackupSelection(
        val settings: Boolean,
        val apps: Boolean,
        val profiles: Boolean
    )

    suspend fun exportConfig(
        context: Context,
        database: AppDatabase,
        selection: BackupSelection
    ): Uri = withContext(Dispatchers.IO) {
        val backup = database.withTransaction {
            val appDao = database.appDao()
            val settingsDao = database.settingsDao()
            val profileDao = database.profileDao()
            AppConfigBackup(
                version = AppConfigBackup.CURRENT_VERSION,
                apps = if (selection.apps) {
                    appDao.getAllAppsSnapshot()
                        .sortedBy { it.packageName }
                        .associate { it.packageName to it.connectionType.name }
                } else {
                    null
                },
                settings = if (selection.settings) {
                    (settingsDao.getSettings() ?: SettingsEntity()).toBackupSettings()
                } else {
                    null
                },
                profiles = if (selection.profiles) {
                    profileDao.getAll().map { it.toBackupProfile() }
                } else {
                    null
                }
            )
        }
        val out = freshShareFile(context, prefix = "frkn-config", extension = "json")
        out.writeText(json.encodeToString(AppConfigBackup.serializer(), backup))
        uriFor(context, out)
    }

    suspend fun inspectBackup(context: Context, uri: Uri): AppConfigBackup? =
        withContext(Dispatchers.IO) {
            val text = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBounded(AppConfigBackup.MAX_IMPORT_BYTES).decodeToString()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return@withContext null
            decodeBackup(text)
        }

    data class ImportResult(
        val applied: Int = 0,
        val skipped: Int = 0,
        val settingsApplied: Boolean = false,
        val profilesAdded: Int = 0,
        val profilesSkipped: Int = 0,
        val error: String? = null
    )

    suspend fun applyBackup(
        database: AppDatabase,
        backup: AppConfigBackup,
        selection: BackupSelection
    ): ImportResult = withContext(Dispatchers.IO) {
        if (!isValidBackup(backup)) return@withContext ImportResult(error = "Invalid backup")
        try {
            database.withTransaction {
                applyBackupInTransaction(database, backup, selection)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ImportResult(error = error.message ?: "Import failed")
        }
    }

    private suspend fun applyBackupInTransaction(
        database: AppDatabase,
        backup: AppConfigBackup,
        selection: BackupSelection
    ): ImportResult {
        val appDao = database.appDao()
        val settingsDao = database.settingsDao()
        val profileDao = database.profileDao()

        var applied = 0
        var skipped = 0
        val apps = backup.apps
        if (selection.apps && apps != null) {
            val existing = appDao.getAllAppsSnapshot().associateBy { it.packageName }
            val updates = mutableListOf<App>()
            for ((packageName, typeName) in apps) {
                val app = existing[packageName]
                val type = ConnectionType.entries.firstOrNull { it.name == typeName }
                if (app == null || type == null) {
                    skipped++
                    continue
                }
                if (app.connectionType != type) updates.add(app.copy(connectionType = type))
                applied++
            }
            if (updates.isNotEmpty()) appDao.upsertApps(updates)
        }

        var settingsApplied = false
        val settings = backup.settings
        if (selection.settings && settings != null) {
            settingsDao.upsertSettings(settings.toEntity())
            settingsApplied = true
        }

        var profilesAdded = 0
        var profilesSkipped = 0
        val profiles = backup.profiles
        if (selection.profiles && profiles != null) {
            val existingByLink = profileDao.getAll().associateByTo(HashMap()) { it.link }
            var importedSelectedId: Long? = null
            for (profile in profiles) {
                val existing = existingByLink[profile.link]
                if (existing != null) {
                    if (profile.selected) importedSelectedId = existing.id
                    profilesSkipped++
                    continue
                }
                val parsed = checkNotNull(LinkParser.parse(profile.link))
                val canonicalOutboundJson = json.encodeToString(
                    JsonObject.serializer(),
                    parsed.outbound
                )
                val entity = profile.toEntity(canonicalOutboundJson)
                val id = profileDao.insert(entity)
                existingByLink[profile.link] = entity.copy(id = id)
                if (profile.selected) importedSelectedId = id
                profilesAdded++
            }
            if (profileDao.getSelected() == null) {
                val selectedId = importedSelectedId ?: profileDao.getAll().firstOrNull()?.id
                selectedId?.let { profileDao.selectExclusive(it) }
            }
        }

        return ImportResult(applied, skipped, settingsApplied, profilesAdded, profilesSkipped)
    }

    internal fun decodeBackup(text: String): AppConfigBackup? =
        runCatching { json.decodeFromString(AppConfigBackup.serializer(), text) }
            .getOrNull()
            ?.takeIf(::isValidBackup)

    internal fun isValidBackup(backup: AppConfigBackup): Boolean {
        if (backup.version !in AppConfigBackup.MIN_SUPPORTED_VERSION..AppConfigBackup.CURRENT_VERSION) {
            return false
        }
        if (backup.apps == null && backup.settings == null && backup.profiles == null) return false
        if ((backup.apps?.size ?: 0) > AppConfigBackup.MAX_ENTRIES_PER_SECTION) return false
        if ((backup.profiles?.size ?: 0) > AppConfigBackup.MAX_ENTRIES_PER_SECTION) return false
        if ((backup.profiles?.count { it.selected } ?: 0) > 1) return false
        if (backup.apps?.any(::isInvalidAppBackup) == true) return false
        if (backup.settings?.let(::isValidSettingsBackup) == false) return false
        if (backup.profiles?.any { !isValidProfileBackup(it) } == true) return false
        return true
    }

    private fun isInvalidAppBackup(entry: Map.Entry<String, String>): Boolean {
        val packageName = entry.key
        return packageName.isBlank() ||
            packageName.length > MAX_PACKAGE_NAME_LENGTH ||
            packageName.any(Char::isWhitespace) ||
            ConnectionType.entries.none { it.name == entry.value }
    }

    private fun isValidSettingsBackup(settings: BackupSettings): Boolean =
        settings.mtu in 1280..9000 &&
            settings.byeDpiArgs.length <= MAX_BYEDPI_ARGS_LENGTH &&
            TunStack.entries.any { it.wire == settings.tunStack } &&
            Ipv6Mode.entries.any { it.wire == settings.ipv6Mode } &&
            isValidDns(settings.dnsRemote) &&
            isValidDns(settings.dnsDirect) &&
            (settings.preferredFingerprint.isBlank() ||
                TlsFingerprint.entries.any { it.wire == settings.preferredFingerprint })

    private fun isValidDns(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_DNS_LENGTH && value.none(Char::isWhitespace)

    private fun isValidProfileBackup(profile: BackupProfile): Boolean {
        if (profile.name.isBlank() || profile.name.length > MAX_PROFILE_NAME_LENGTH) return false
        if (profile.link.isBlank() || profile.link.length > MAX_LINK_LENGTH) return false
        if (profile.outboundJson.isBlank() || profile.outboundJson.length > MAX_OUTBOUND_LENGTH) return false
        if (!isValidSubscriptionUrl(profile.subscriptionUrl)) return false

        val parsed = LinkParser.parse(profile.link) ?: return false
        if (parsed.type != profile.type) return false
        val outbound = runCatching { json.parseToJsonElement(profile.outboundJson) as? JsonObject }
            .getOrNull() ?: return false
        val type = runCatching { outbound["type"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        val server = runCatching { outbound["server"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        val port = runCatching { outbound["server_port"]?.jsonPrimitive?.intOrNull }.getOrNull()
        val parsedServer = parsed.outbound["server"]?.jsonPrimitive?.contentOrNull
        val parsedPort = parsed.outbound["server_port"]?.jsonPrimitive?.intOrNull
        return type == profile.type &&
            server == parsedServer &&
            port == parsedPort &&
            !server.isNullOrBlank() &&
            port != null &&
            port in 1..65535
    }

    private fun isValidSubscriptionUrl(value: String): Boolean {
        if (value.isBlank()) return true
        if (value.length > MAX_LINK_LENGTH) return false
        return runCatching {
            val uri = URI(value)
            uri.scheme?.lowercase() in setOf("http", "https") && !uri.rawAuthority.isNullOrBlank()
        }.getOrDefault(false)
    }

    internal fun InputStream.readBounded(maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            total += read
            require(total <= maxBytes) { "Backup exceeds ${maxBytes / 1024 / 1024} MB" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun freshShareFile(context: Context, prefix: String, extension: String): File {
        val directory = shareDir(context)
        val staleBefore = System.currentTimeMillis() - SHARE_FILE_MAX_AGE_MS
        directory.listFiles()?.forEach { file ->
            if (
                file.isFile &&
                file.name.startsWith("$prefix-") &&
                file.name.endsWith(".$extension") &&
                file.lastModified() < staleBefore
            ) {
                file.delete()
            }
        }
        val uniqueSuffix = UUID.randomUUID().toString().take(8)
        return File(directory, "$prefix-${timestamp()}-$uniqueSuffix.$extension")
    }

    fun shareIntent(uri: Uri, mimeType: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
