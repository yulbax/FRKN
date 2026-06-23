package io.github.yulbax.frkn.util

import android.content.Context
import android.util.Log
import org.koin.core.annotation.Single
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Single
class FrknLog(context: Context) {

    private val logFile = File(context.applicationContext.filesDir, "frkn.log")
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        write("I", tag, message, null)
    }

    fun w(tag: String, message: String, t: Throwable? = null) {
        Log.w(tag, message, t)
        write("W", tag, message, t)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        Log.e(tag, message, t)
        write("E", tag, message, t)
    }

    
    fun dump(): String = synchronized(lock) {
        if (logFile.exists()) logFile.readText() else "(empty)"
    }

    private fun write(level: String, tag: String, message: String, t: Throwable?) {
        val line = buildString {
            append(timeFormat.format(Date()))
            append(' ').append(level)
            append('/').append(tag)
            append(": ").append(message)
            if (t != null) {
                append('\n')
                append(Log.getStackTraceString(t).trimEnd())
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

    private companion object {
        const val MAX_BYTES = 1024 * 1024L
    }
}
