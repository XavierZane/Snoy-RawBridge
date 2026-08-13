package com.rawbridge.backend.debug

import android.util.Log
import com.rawbridge.backend.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UsbDebugLogEntry(
    val id: Long,
    val timestampMillis: Long,
    val level: UsbDebugLogLevel,
    val tag: String,
    val message: String,
)

enum class UsbDebugLogLevel {
    Debug,
    Warn,
    Error,
}

object UsbDebugLogger {
    private const val MaxEntries = 400

    private val lock = Any()
    private val entries = MutableStateFlow<List<UsbDebugLogEntry>>(emptyList())
    private var nextId: Long = 0

    val logs: StateFlow<List<UsbDebugLogEntry>> = entries.asStateFlow()

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append(UsbDebugLogLevel.Debug, tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        append(UsbDebugLogLevel.Warn, tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        append(
            level = UsbDebugLogLevel.Error,
            tag = tag,
            message = buildString {
                append(message)
                val throwableMessage = throwable?.message
                if (!throwableMessage.isNullOrBlank()) {
                    append(" | ")
                    append(throwableMessage)
                }
            },
        )
    }

    fun clear() {
        synchronized(lock) {
            entries.value = emptyList()
        }
    }

    private fun append(
        level: UsbDebugLogLevel,
        tag: String,
        message: String,
    ) {
        if (!BuildConfig.DEBUG) {
            return
        }

        synchronized(lock) {
            nextId += 1
            val entry = UsbDebugLogEntry(
                id = nextId,
                timestampMillis = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = message,
            )
            entries.value = (entries.value + entry).takeLast(MaxEntries)
        }
    }
}
