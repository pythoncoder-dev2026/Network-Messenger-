package com.network.messenger.data.repository

import com.network.messenger.BuildConfig
import com.network.messenger.domain.model.DeliveryState
import com.network.messenger.domain.model.LogCategory
import com.network.messenger.domain.model.LogEntry
import com.network.messenger.domain.model.LogSeverity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogRepository @Inject constructor() {
    private val idGen = AtomicLong(1L)
    private var identitySuffix: String = ""

    private val _entries = MutableStateFlow<List<LogEntry>>(listOf(buildHeaderEntry()))
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun setIdentityTag(shortUserId: String) {
        identitySuffix = " | uid=$shortUserId"
        _entries.update { list ->
            val header = buildHeaderEntry()
            if (list.isEmpty()) listOf(header)
            else listOf(header) + list.drop(1)
        }
    }

    fun log(
        category: LogCategory,
        message: String,
        severity: LogSeverity = LogSeverity.INFO,
        packetId: String? = null,
        deliveryState: DeliveryState? = null
    ) {
        val entry = LogEntry(
            id = idGen.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            category = category,
            severity = severity,
            message = message,
            packetId = packetId,
            deliveryState = deliveryState
        )
        _entries.update { current ->
            val header = current.firstOrNull { it.isHeader } ?: buildHeaderEntry()
            val body = current.filterNot { it.isHeader } + entry
            val trimmed = if (body.size > MAX_LOG_ENTRIES) {
                body.takeLast(MAX_LOG_ENTRIES)
            } else {
                body
            }
            listOf(header) + trimmed
        }
    }

    fun updatePacketDeliveryState(packetId: String, newState: DeliveryState) {
        _entries.update { current ->
            current.map { entry ->
                if (entry.packetId == packetId && shouldAdvanceState(entry.deliveryState, newState)) {
                    entry.copy(deliveryState = newState)
                } else {
                    entry
                }
            }
        }
    }

    private fun shouldAdvanceState(current: DeliveryState?, incoming: DeliveryState): Boolean {
        if (current == null) return true
        if (incoming == DeliveryState.FAILED) return current == DeliveryState.QUEUED
        val rank = mapOf(
            DeliveryState.QUEUED to 0,
            DeliveryState.SENT to 1,
            DeliveryState.RELAYED to 2,
            DeliveryState.DELIVERED to 3,
            DeliveryState.READ to 4,
            DeliveryState.FAILED to -1
        )
        return (rank[incoming] ?: 0) >= (rank[current] ?: 0)
    }

    fun info(
        category: LogCategory,
        message: String,
        packetId: String? = null,
        deliveryState: DeliveryState? = null
    ) = log(category, message, LogSeverity.INFO, packetId, deliveryState)

    fun success(
        category: LogCategory,
        message: String,
        packetId: String? = null,
        deliveryState: DeliveryState? = null
    ) = log(category, message, LogSeverity.SUCCESS, packetId, deliveryState)

    fun warn(
        category: LogCategory,
        message: String,
        packetId: String? = null,
        deliveryState: DeliveryState? = null
    ) = log(category, message, LogSeverity.WARN, packetId, deliveryState)

    fun error(
        category: LogCategory,
        message: String,
        packetId: String? = null,
        deliveryState: DeliveryState? = null
    ) = log(category, message, LogSeverity.ERROR, packetId, deliveryState)

    fun clear() {
        val header = buildHeaderEntry()
        val clearedNote = LogEntry(
            id = idGen.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SYSTEM,
            severity = LogSeverity.INFO,
            message = "Log buffer cleared"
        )
        _entries.value = listOf(header, clearedNote)
    }

    fun formatAsPlainText(filter: LogCategory? = null): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val snapshot = _entries.value
        val filtered = if (filter == null) snapshot else snapshot.filter { it.isHeader || it.category == filter }
        return buildString {
            filtered.forEach { entry ->
                val ts = formatter.format(Date(entry.timestampMs))
                append('[')
                append(ts)
                append("] [")
                append(entry.category.tag)
                append("] ")
                appendLine(entry.message)
            }
        }
    }

    private fun buildHeaderEntry(): LogEntry = LogEntry(
        id = 0L,
        timestampMs = System.currentTimeMillis(),
        category = LogCategory.SYSTEM,
        severity = LogSeverity.SUCCESS,
        message = "$APP_DISPLAY_NAME v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})$identitySuffix",
        isHeader = true
    )

    companion object {
        const val APP_DISPLAY_NAME = "Network messenger"
        private const val MAX_LOG_ENTRIES = 1500
    }
}
