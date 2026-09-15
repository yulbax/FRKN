package io.github.yulbax.frkn.util

import android.content.Context
import android.util.Log
import org.koin.core.annotation.Single
import java.io.File

@Single(binds = [AppLog::class])
class FrknLog(context: Context) : AppLog {
    private val file = FileAppLog(File(context.applicationContext.filesDir, "frkn.log")) { level, tag, text ->
        when (level) {
            LogLevel.INFO -> Log.i(tag, text)
            LogLevel.WARN -> Log.w(tag, text)
            LogLevel.ERROR -> Log.e(tag, text)
        }
    }

    override fun i(tag: String, message: String) = file.i(tag, message)

    override fun w(tag: String, message: String, t: Throwable?) = file.w(tag, message, t)

    override fun e(tag: String, message: String, t: Throwable?) = file.e(tag, message, t)

    fun dump(): String = file.dump()
}
