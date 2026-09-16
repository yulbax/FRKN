package io.github.yulbax.frkn.desktop

import io.github.yulbax.frkn.util.AppLog
import io.github.yulbax.frkn.vpn.core.ByeDpiProcess
import java.io.File
import java.util.concurrent.TimeUnit

class CiadpiProcess(private val binary: File, private val log: AppLog) : ByeDpiProcess {
    @Volatile private var process: Process? = null

    override val isRunning: Boolean get() = process != null

    @Synchronized
    override fun start(port: Int, args: List<String>, onUnexpectedExit: (code: Int) -> Unit) {
        if (process != null) return
        check(binary.isFile) { "ciadpi binary not found: ${binary.absolutePath}" }
        val started = ProcessBuilder(listOf(binary.absolutePath, "-i", "127.0.0.1", "-p", port.toString()) + args)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        process = started
        log.i(TAG, "ciadpi started on port $port args=$args")
        started.onExit().thenAccept { exited ->
            if (process === exited) onUnexpectedExit(exited.exitValue())
        }
    }

    @Synchronized
    override fun stop() {
        val current = process ?: return
        process = null
        current.destroy()
        if (!current.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) current.destroyForcibly()
    }

    private companion object {
        const val TAG = "Ciadpi"
        const val STOP_TIMEOUT_MS = 3_000L
    }
}
