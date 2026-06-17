package com.example.hiderecents

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object AppLogger {
    private val logs = mutableListOf<String>()
    private val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var logFile: File? = null

    fun init(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "logs")
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, "systemtool_${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())}.log")
    }

    fun log(tag: String, msg: String) {
        val time = sdf.format(Date())
        val entry = "$time [$tag] $msg"
        synchronized(logs) {
            logs.add(entry)
            if (logs.size > 2000) logs.removeAt(0)
        }
        try {
            logFile?.appendText("$entry\n")
        } catch (_: Exception) {}
    }

    fun d(msg: String) = log("DEBUG", msg)
    fun i(msg: String) = log("INFO", msg)
    fun w(msg: String) = log("WARN", msg)
    fun e(msg: String) = log("ERROR", msg)
    fun e(msg: String, t: Throwable) = log("ERROR", "$msg: ${t.message}\n${t.stackTraceToString()}")

    fun getAll(): String {
        synchronized(logs) {
            return logs.joinToString("\n")
        }
    }

    fun getLogFile(): File? = logFile

    fun clear() {
        synchronized(logs) { logs.clear() }
        try { logFile?.writeText("") } catch (_: Exception) {}
    }
}
