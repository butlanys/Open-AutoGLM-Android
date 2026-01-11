package com.autoglm.android.data

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

object CrashHandler : Thread.UncaughtExceptionHandler {
    private const val TAG = "CrashHandler"
    private const val CRASH_DIR = "crash_logs"
    private const val MAX_CRASH_FILES = 10
    
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var appContext: Context
    
    fun init(context: Context) {
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.i(TAG, "CrashHandler initialized")
    }
    
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            saveCrashLog(thread, throwable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log", e)
        }
        
        defaultHandler?.uncaughtException(thread, throwable)
    }
    
    private fun saveCrashLog(thread: Thread, throwable: Throwable) {
        val crashDir = File(appContext.filesDir, CRASH_DIR)
        if (!crashDir.exists()) {
            crashDir.mkdirs()
        }
        
        cleanOldCrashFiles(crashDir)
        
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val crashFile = File(crashDir, "crash_$timestamp.txt")
        
        val content = buildString {
            appendLine("=== Crash Report ===")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())}")
            appendLine("Thread: ${thread.name} (id=${thread.id})")
            appendLine()
            appendLine("=== Device Info ===")
            appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("App Version: ${getAppVersion()}")
            appendLine()
            appendLine("=== Recent Logs ===")
            appendLine(LogManager.getLogsAsText())
            appendLine()
            appendLine("=== Stack Trace ===")
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            appendLine(sw.toString())
        }
        
        crashFile.writeText(content)
        Log.i(TAG, "Crash log saved to: ${crashFile.absolutePath}")
    }
    
    private fun getAppVersion(): String {
        return try {
            val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.longVersionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    private fun cleanOldCrashFiles(crashDir: File) {
        val files = crashDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        if (files.size > MAX_CRASH_FILES) {
            files.drop(MAX_CRASH_FILES).forEach { it.delete() }
        }
    }
    
    fun getCrashLogs(): List<CrashLog> {
        val crashDir = File(appContext.filesDir, CRASH_DIR)
        if (!crashDir.exists()) return emptyList()
        
        return crashDir.listFiles()
            ?.filter { it.name.startsWith("crash_") && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                CrashLog(
                    fileName = file.name,
                    timestamp = file.lastModified(),
                    content = file.readText()
                )
            } ?: emptyList()
    }
    
    fun getCrashLogsAsText(): String {
        val logs = getCrashLogs()
        if (logs.isEmpty()) return "No crash logs found."
        
        return logs.joinToString("\n\n${"=".repeat(60)}\n\n") { it.content }
    }
    
    fun clearCrashLogs() {
        val crashDir = File(appContext.filesDir, CRASH_DIR)
        crashDir.listFiles()?.forEach { it.delete() }
    }
    
    fun hasCrashLogs(): Boolean {
        val crashDir = File(appContext.filesDir, CRASH_DIR)
        return crashDir.exists() && (crashDir.listFiles()?.isNotEmpty() == true)
    }
}

data class CrashLog(
    val fileName: String,
    val timestamp: Long,
    val content: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
