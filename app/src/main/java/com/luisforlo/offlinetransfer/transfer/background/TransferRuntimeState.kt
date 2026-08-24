package com.luisforlo.offlinetransfer.transfer.background

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BackgroundTransferPhase {
    IDLE,
    PREPARING,
    WAITING,
    TRANSFERRING,
    COMPLETE,
    PAUSED,
    CANCELLED,
    ERROR,
}

enum class BackgroundTransferDirection {
    SEND,
    RECEIVE,
}

data class BackgroundHistoryEntry(
    val id: Long,
    val direction: String,
    val fileName: String,
    val sizeBytes: Long,
    val detail: String,
)

data class TransferRuntimeState(
    val phase: BackgroundTransferPhase = BackgroundTransferPhase.IDLE,
    val direction: BackgroundTransferDirection? = null,
    val message: String = "Sin transferencia activa",
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val currentFile: String? = null,
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val speedBytesPerSecond: Double = 0.0,
    val etaSeconds: Long? = null,
    val diagnostics: String? = null,
    val encrypted: Boolean = false,
    val verificationCode: String? = null,
    val resumedFromBytes: Long = 0L,
    val partialCount: Int = 0,
    val partialBytes: Long = 0L,
    val history: List<BackgroundHistoryEntry> = emptyList(),
) {
    val busy: Boolean
        get() = phase == BackgroundTransferPhase.PREPARING ||
            phase == BackgroundTransferPhase.WAITING ||
            phase == BackgroundTransferPhase.TRANSFERRING
}

/**
 * Process-wide observable transfer state. The foreground service owns writes;
 * Activities may disappear/reappear without owning or cancelling the transfer.
 */
object TransferRuntimeStore {
    private val _state = MutableStateFlow(TransferRuntimeState())
    val state: StateFlow<TransferRuntimeState> = _state.asStateFlow()

    fun set(value: TransferRuntimeState) {
        _state.value = value
    }

    fun update(transform: (TransferRuntimeState) -> TransferRuntimeState) {
        _state.value = transform(_state.value)
    }

    fun reset(message: String = "Sin transferencia activa") {
        val previous = _state.value
        _state.value = TransferRuntimeState(
            message = message,
            partialCount = previous.partialCount,
            partialBytes = previous.partialBytes,
            history = previous.history,
        )
    }
}
