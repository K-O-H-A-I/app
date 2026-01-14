package com.sitta.app

import android.app.Application
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SittaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val crashFile = File(filesDir, "crash_$timestamp.txt")
                val payload = buildString {
                    appendLine("Time: $timestamp")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Exception: ${throwable.javaClass.name}")
                    appendLine("Message: ${throwable.message}")
                    appendLine("Stacktrace:")
                    appendLine(throwable.stackTraceToString())
                }
                crashFile.writeText(payload)
            } catch (e: Exception) {
                Log.e("SITTA/Crash", "Failed to write crash log", e)
            }
            Log.e("SITTA/Crash", "Uncaught exception", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
