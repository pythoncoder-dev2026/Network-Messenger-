package com.network.messenger.domain.model

enum class LogCategory(val tag: String, val filterLabel: String) {
    SYSTEM("SYS", "System"),
    TRANSPORT("XPORT", "Transport"),
    PEER("PEER", "Peer"),
    NETWORK("NET", "Network"),
    CRYPTO("CRYPTO", "Crypto"),
    MESSAGE("MSG", "Message"),
    VOICE("VOICE", "Voice"),
    BLOCK("BLOCK", "Block"),
    PERMISSION("PERM", "Perm"),
    ERROR("ERR", "Error")
}

enum class LogSeverity {
    INFO,
    SUCCESS,
    WARN,
    ERROR
}

data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val category: LogCategory,
    val severity: LogSeverity,
    val message: String,
    val isHeader: Boolean = false,
    val packetId: String? = null,
    val deliveryState: DeliveryState? = null
)
