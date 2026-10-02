package com.example.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the stack trace of any crash to a file so the next app start can show it on screen.
 * Useful when there is no PC / logcat available.
 */
object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = buildString {
                    append("Time: ").append(time).append('\n')
                    append("Thread: ").append(thread.name).append('\n')
                    append("Device: ").append(android.os.Build.MANUFACTURER).append(' ')
                        .append(android.os.Build.MODEL).append(" / Android ")
                        .append(android.os.Build.VERSION.RELEASE).append(" (API ")
                        .append(android.os.Build.VERSION.SDK_INT).append(")\n\n")
                    append(sw.toString().take(12000))
                }
                File(appContext.filesDir, FILE_NAME).writeText(text)
            } catch (e: Throwable) {
                // never crash inside the crash handler
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun readLastCrash(context: Context): String? {
        return try {
            val f = File(context.applicationContext.filesDir, FILE_NAME)
            if (f.exists()) f.readText().ifBlank { null } else null
        } catch (e: Throwable) {
            null
        }
    }

    fun clear(context: Context) {
        try {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        } catch (e: Throwable) {
            // ignore
        }
    }
}
