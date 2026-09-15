package io.github.yulbax.frkn.util

import java.io.File
import java.util.Date

object DiagnosticsReport {
    private const val MAX_BOX_LOG_BYTES = 1024 * 1024L

    fun build(environment: List<Pair<String, String>>, appLog: String, boxLog: File): String = buildString {
        append("=== FRKN diagnostics ===\n")
        append("time: ").append(Date()).append('\n')
        environment.forEach { (key, value) -> append(key).append(": ").append(value).append('\n') }
        append("\n=== app log (frkn.log) ===\n")
        append(redactSensitiveData(appLog))
        append("\n=== sing-box (box.log) ===\n")
        append(if (boxLog.exists()) redactSensitiveData(tail(boxLog, MAX_BOX_LOG_BYTES)) else "(no box.log)")
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
}
