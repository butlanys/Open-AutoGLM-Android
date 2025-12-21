package com.autoglm.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

data class AppLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO,
    val tag: String,
    val message: String,
    val throwable: String? = null
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
    
    val formattedDate: String
        get() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

enum class LogLevel(val label: String) {
    DEBUG("D"),
    INFO("I"),
    WARN("W"),
    ERROR("E")
}

object LogManager {
    private const val MAX_LOGS = 500
    
    private val _logs = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val logs: StateFlow<List<AppLogEntry>> = _logs.asStateFlow()
    
    fun d(tag: String, message: String) {
        addLog(LogLevel.DEBUG, tag, message)
    }
    
    fun i(tag: String, message: String) {
        addLog(LogLevel.INFO, tag, message)
    }
    
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        addLog(LogLevel.WARN, tag, message, throwable)
    }
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        addLog(LogLevel.ERROR, tag, message, throwable)
    }
    
    private fun addLog(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        val entry = AppLogEntry(
            level = level,
            tag = tag,
            message = message,
            throwable = throwable?.stackTraceToString()
        )
        
        _logs.value = (_logs.value + entry).takeLast(MAX_LOGS)
        
        // Also log to Android logcat
        when (level) {
            LogLevel.DEBUG -> android.util.Log.d(tag, message, throwable)
            LogLevel.INFO -> android.util.Log.i(tag, message, throwable)
            LogLevel.WARN -> android.util.Log.w(tag, message, throwable)
            LogLevel.ERROR -> android.util.Log.e(tag, message, throwable)
        }
    }
    
    fun clear() {
        _logs.value = emptyList()
    }
    
    fun getLogsAsText(): String {
        return _logs.value.joinToString("\n") { log ->
            "${log.formattedDate} ${log.level.label}/${log.tag}: ${log.message}" +
                    (log.throwable?.let { "\n$it" } ?: "")
        }
    }
}
