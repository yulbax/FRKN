package io.github.yulbax.frkn.vpn

import android.annotation.SuppressLint
import android.net.LocalServerSocket
import android.net.LocalSocket
import io.github.yulbax.frkn.util.FrknLog
import java.io.FileDescriptor
import kotlin.concurrent.thread

class SocketProtector(
    val name: String,
    private val log: FrknLog,
    private val protect: (Int) -> Boolean
) {
    private var serverSocket: LocalServerSocket? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var loggedFailure = false

    fun start() {
        if (running) return
        running = true
        serverSocket = LocalServerSocket(name)
        worker = thread(name = "frkn-protect") {
            val server = serverSocket ?: return@thread
            while (running) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                handle(socket)
            }
        }
    }

    private fun handle(socket: LocalSocket) {
        try {
            socket.inputStream.read()
            val fds = socket.ancillaryFileDescriptors
            val ok = if (!fds.isNullOrEmpty()) {
                val intFd = fdToInt(fds[0])
                val result = intFd >= 0 && protect(intFd)
                fds.forEach { runCatching { closeFd(it) } }
                result
            } else {
                logFailureOnce("no ancillary fds received", null)
                false
            }
            socket.outputStream.write(if (ok) 0 else 1)
            socket.outputStream.flush()
        } catch (e: Exception) {
            logFailureOnce("protect handling failed", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun logFailureOnce(message: String, t: Throwable?) {
        if (loggedFailure) return
        loggedFailure = true
        log.w(TAG, "$message (further occurrences suppressed)", t)
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        runCatching { worker?.join(1000) }
        serverSocket = null
        worker = null
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun fdToInt(fd: FileDescriptor): Int = runCatching {
        val field = FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        field.getInt(fd)
    }.getOrDefault(-1)

    private fun closeFd(fd: FileDescriptor) {
        android.system.Os.close(fd)
    }

    companion object {
        private const val TAG = "SocketProtector"
    }
}
