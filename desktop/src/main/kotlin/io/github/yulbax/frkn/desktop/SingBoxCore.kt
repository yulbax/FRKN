package io.github.yulbax.frkn.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import java.io.File

class SingBoxCore(library: File) {

    @Suppress("FunctionName")
    private interface Api : Library {
        fun frkn_version(): Pointer
        fun frkn_check(config: String): Pointer?
        fun frkn_start(config: String, workDir: String): Pointer?
        fun frkn_stop(): Pointer?
        fun frkn_select(group: String, outbound: String): Int
        fun frkn_delay(outbound: String, url: String, timeoutMs: Int): Int
        fun frkn_traffic(up: LongByReference, down: LongByReference): Int
        fun frkn_free(value: Pointer)
    }

    data class Traffic(val up: Long, val down: Long)

    private val api: Api by lazy {
        check(library.isFile) { "sing-box core not found: ${library.absolutePath}" }
        Native.load(library.absolutePath, Api::class.java, mapOf(Library.OPTION_STRING_ENCODING to Charsets.UTF_8.name()))
    }

    val version: String get() = take(api.frkn_version()).orEmpty()

    fun check(config: String) = fail(api.frkn_check(config))

    fun start(config: String, workDir: File) = fail(api.frkn_start(config, workDir.absolutePath))

    fun stop() = fail(api.frkn_stop())

    fun select(group: String, outbound: String): Boolean = api.frkn_select(group, outbound) == 1

    fun delay(outbound: String, url: String, timeoutMs: Int): Int? =
        api.frkn_delay(outbound, url, timeoutMs).takeIf { it > 0 }

    fun traffic(): Traffic? {
        val up = LongByReference()
        val down = LongByReference()
        return if (api.frkn_traffic(up, down) == 1) Traffic(up.value, down.value) else null
    }

    private fun fail(error: Pointer?) {
        take(error)?.let { throw IllegalStateException(it) }
    }

    private fun take(value: Pointer?): String? = value?.let {
        try {
            it.getString(0, Charsets.UTF_8.name())
        } finally {
            api.frkn_free(it)
        }
    }
}
