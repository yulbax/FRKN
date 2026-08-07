package io.github.yulbax.frkn.util

import android.content.Context
import android.util.Log
import org.koin.core.annotation.Single
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SENSITIVE_URI = Regex(
    "(?i)\\b(https?|vless|vmess|trojan|ss|hysteria2|hy2)://[^\\s)\\]}]+"
)
private val SENSITIVE_JSON_FIELD = Regex(
    """(?i)("(?:password|uuid|token|authorization|private_key)"\s*:\s*")(?:\\.|[^"\\])*(")"""
)
private val AUTHORIZATION_HEADER = Regex("(?im)\\b(authorization:\\s*)[^\\r\\n]+")

internal fun redactSensitiveData(value: String): String {
    val withoutUris = SENSITIVE_URI.replace(value) { match ->
        "${match.groupValues[1]}://<redacted>"
    }
    val withoutJsonSecrets = SENSITIVE_JSON_FIELD.replace(withoutUris) { match ->
        "${match.groupValues[1]}<redacted>${match.groupValues[2]}"
    }
    return AUTHORIZATION_HEADER.replace(withoutJsonSecrets) { match ->
        "${match.groupValues[1]}<redacted>"
    }
}

@Single
class FrknLog(context: Context) {

    private val logFile = File(context.applicationContext.filesDir, "frkn.log")
    private val timeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)
        .withZone(ZoneId.systemDefault())
    private val lock = Any()

    fun i(tag: String, message: String) {
        Log.i(tag, redactSensitiveData(message))
        write("I", tag, message, null)
    }

    fun w(tag: String, message: String, t: Throwable? = null) {
        Log.w(tag, logcatMessage(message, t))
        write("W", tag, message, t)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        Log.e(tag, logcatMessage(message, t))
        write("E", tag, message, t)
    }
    fun dump(): String = synchronized(lock) {
        if (logFile.exists()) logFile.readText() else "(empty)"
    }

    private fun write(level: String, tag: String, message: String, t: Throwable?) {
        val line = buildString {
            append(timeFormat.format(Instant.now()))
            append(' ').append(level)
            append('/').append(tag)
            append(": ").append(redactSensitiveData(message))
            if (t != null) {
                append('\n')
                append(redactSensitiveData(Log.getStackTraceString(t)).trimEnd())
            }
            append('\n')
        }
        synchronized(lock) {
            runCatching {
                if (logFile.length() > MAX_BYTES) {
                    val rotated = File(logFile.parentFile, logFile.name + ".1")
                    rotated.delete()
                    logFile.renameTo(rotated)
                }
                logFile.appendText(line)
            }
        }
    }

    private fun logcatMessage(message: String, t: Throwable?): String = buildString {
        append(redactSensitiveData(message))
        if (t != null) {
            append('\n')
            append(redactSensitiveData(Log.getStackTraceString(t)).trimEnd())
        }
    }

    private companion object {
        const val MAX_BYTES = 1024 * 1024L
    }
}
