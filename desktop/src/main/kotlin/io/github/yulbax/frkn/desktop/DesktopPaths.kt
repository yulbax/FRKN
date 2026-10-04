package io.github.yulbax.frkn.desktop

import io.github.yulbax.frkn.data.AppDatabase
import java.io.File

object DesktopPaths {
    val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

    val dataDir: File = (System.getenv("APPDATA")?.let { File(it, "FRKN") }
        ?: File(System.getProperty("user.home"), ".local/share/frkn")).apply { mkdirs() }

    val database: File = File(dataDir, AppDatabase.FILE_NAME)
    val logFile: File = File(dataDir, "frkn.log")

    private val binDir: File = System.getProperty("compose.application.resources.dir")?.let(::File)
        ?: System.getenv("FRKN_BIN_DIR")?.let(::File)
        ?: File("binaries")

    val serviceBinary: File = File(binDir, executable("frkn-service"))

    val serviceSocket: File = System.getenv("FRKN_SERVICE_SOCKET")?.let(::File)
        ?: if (isWindows) File(System.getenv("ProgramData") ?: "C:\\ProgramData", "FRKN Service/frkn.sock")
        else File("/run/frkn/frkn.sock")

    val installDir: File? = System.getProperty("compose.application.resources.dir")?.let {
        ProcessHandle.current().info().command().map { command -> File(command).parentFile }.orElse(null)
    }
    val ciadpi: File = File(binDir, executable("ciadpi"))

    val ownExecutable: String? = ProcessHandle.current().info().command().map { File(it).name }.orElse(null)

    private fun executable(name: String): String = if (isWindows) "$name.exe" else name
}
