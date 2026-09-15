package io.github.yulbax.frkn.util

interface AppLog {
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, t: Throwable? = null)
    fun e(tag: String, message: String, t: Throwable? = null)
}
