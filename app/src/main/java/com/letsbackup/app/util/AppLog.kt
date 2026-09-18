package com.letsbackup.app.util

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

object AppLog {
    private val logs = CopyOnWriteArrayList<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private const val MAX_LINES = 500

    fun d(tag: String, message: String) {
        add("D", tag, message)
    }

    fun i(tag: String, message: String) {
        add("I", tag, message)
    }

    fun w(tag: String, message: String) {
        add("W", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        add("E", tag, message + (throwable?.let { " | ${it.message}" } ?: ""))
        throwable?.stackTrace?.take(8)?.forEach {
            add("E", tag, "  at $it")
        }
    }

    private fun add(level: String, tag: String, message: String) {
        val line = "${timeFormat.format(Date())} $level/$tag: $message"
        logs.add(line)
        while (logs.size > MAX_LINES) {
            logs.removeAt(0)
        }
        when (level) {
            "E" -> android.util.Log.e(tag, message)
            "W" -> android.util.Log.w(tag, message)
            "I" -> android.util.Log.i(tag, message)
            else -> android.util.Log.d(tag, message)
        }
    }

    fun getAll(): String = logs.joinToString("\n")

    fun clear() {
        logs.clear()
        i("AppLog", "Logs cleared")
    }
}
