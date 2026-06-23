package io.github.yulbax.frkn.engine

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread


const val BYEDPI_VERSION = "17.3"

class ByeDpi(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val protectPath: String? = null,
    private val extraArgs: List<String> = DEFAULT_DESYNC_ARGS,
    private val onUnexpectedExit: ((Int) -> Unit)? = null
) {
    private val running = AtomicBoolean(false)
    @Volatile private var stopRequested = false
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        stopRequested = false
        val args = buildList {
            add("ciadpi")
            add("-i"); add(host)
            add("-p"); add(port.toString())
            if (protectPath != null) { add("-P"); add(protectPath) }
            addAll(extraArgs)
        }.toTypedArray()

        worker = thread(name = "byedpi") {
            Log.i(TAG, "byedpi starting on $host:$port args=${args.joinToString(" ")}")
            val code = nativeStart(args)
            Log.i(TAG, "byedpi exited with code $code")
            running.set(false)
            if (!stopRequested) onUnexpectedExit?.invoke(code)
        }
    }

    fun stop() {
        if (!running.get()) return
        stopRequested = true
        nativeStop()
        worker?.join(2000)
        worker = null
        running.set(false)
    }

    fun isRunning(): Boolean = running.get()

    private external fun nativeStart(args: Array<String>): Int
    private external fun nativeStop()

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 1081
        private const val TAG = "ByeDpi"

        val DEFAULT_DESYNC_ARGS = listOf(
            "-d1", "-s1+s", "-s3+s", "-s6+s", "-s9+s", "-s12+s", "-s15+s", "-s20+s", "-s30+s", "-a1"
        )

        fun parseArgs(line: String): List<String> {
            if (line.isBlank()) return DEFAULT_DESYNC_ARGS
            val tokens = mutableListOf<String>()
            val sb = StringBuilder()
            var quote = ' '
            for (c in line) {
                when {
                    quote != ' ' -> if (c == quote) quote = ' ' else sb.append(c)
                    c == '"' || c == '\'' -> quote = c
                    c.isWhitespace() -> if (sb.isNotEmpty()) { tokens.add(sb.toString()); sb.clear() }
                    else -> sb.append(c)
                }
            }
            if (sb.isNotEmpty()) tokens.add(sb.toString())
            return if (tokens.isEmpty()) DEFAULT_DESYNC_ARGS else tokens
        }

        init {
            System.loadLibrary("byedpi")
        }
    }
}
