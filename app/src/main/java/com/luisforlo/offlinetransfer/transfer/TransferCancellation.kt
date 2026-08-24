package com.luisforlo.offlinetransfer.transfer

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class TransferCancelledException : IOException("Transferencia cancelada")

/**
 * Cancellation primitive for blocking socket/file operations.
 * Closing tracked resources wakes accept/read/write immediately.
 */
class TransferCancellation {
    private val cancelled = AtomicBoolean(false)
    private val lock = Any()
    private val resources = LinkedHashSet<Closeable>()

    val isCancelled: Boolean
        get() = cancelled.get()

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return

        val toClose = synchronized(lock) {
            resources.toList().also { resources.clear() }
        }
        toClose.forEach { resource -> runCatching { resource.close() } }
    }

    fun throwIfCancelled() {
        if (isCancelled) throw TransferCancelledException()
    }

    internal fun track(resource: Closeable) {
        synchronized(lock) {
            if (isCancelled) {
                runCatching { resource.close() }
            } else {
                resources += resource
            }
        }
        throwIfCancelled()
    }

    internal fun untrack(resource: Closeable) {
        synchronized(lock) {
            resources -= resource
        }
    }
}
