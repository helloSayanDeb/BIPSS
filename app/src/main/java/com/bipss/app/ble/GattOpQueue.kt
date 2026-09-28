package com.bipss.app.ble

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "GattOpQueue"

/**
 * Runs GATT operations one at a time.
 *
 * Android's GATT client allows a single outstanding operation: issue a second
 * descriptor write before the first one's callback and it returns false and is
 * silently dropped. Subscribing to three characteristics back to back used to
 * do exactly that - usually only the first subscription took, and which one
 * "won" depended on timing.
 *
 * Each [Op.start] issues one request and returns whether Android accepted it.
 * The matching GATT callback calls [complete]; a timeout does the same if the
 * callback never comes (a link that dropped mid-operation), so one lost
 * callback cannot wedge the queue.
 */
class GattOpQueue(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = OP_TIMEOUT_MS
) {
    /**
     * @param awaitsCallback false for a request with no completion callback
     *   (connection priority): the queue moves on as soon as it is issued.
     */
    class Op(val label: String, val awaitsCallback: Boolean = true, val start: () -> Boolean)

    private val lock = Any()
    private val pending = ArrayDeque<Op>()
    private var current: Op? = null
    private var timeoutJob: Job? = null

    fun enqueue(op: Op) {
        synchronized(lock) {
            pending.addLast(op)
            if (current == null) runNextLocked()
        }
    }

    /** Called from the GATT callback that ends the current operation. */
    fun complete() {
        synchronized(lock) {
            timeoutJob?.cancel()
            timeoutJob = null
            current = null
            runNextLocked()
        }
    }

    /** Drops everything - the link is gone and the queued operations refer to a dead handle. */
    fun clear() {
        synchronized(lock) {
            timeoutJob?.cancel()
            timeoutJob = null
            current = null
            pending.clear()
        }
    }

    private fun runNextLocked() {
        while (pending.isNotEmpty()) {
            val op = pending.removeFirst()
            val accepted = runCatching { op.start() }
                .onFailure { Log.w(TAG, "${op.label} threw", it) }
                .getOrDefault(false)
            if (accepted && !op.awaitsCallback) continue
            if (accepted) {
                current = op
                timeoutJob = scope.launch {
                    delay(timeoutMs)
                    synchronized(lock) {
                        // The real callback may have landed while this woke up.
                        if (current !== op) return@launch
                        Log.w(TAG, "${op.label} timed out after $timeoutMs ms")
                        current = null
                        timeoutJob = null
                        runNextLocked()
                    }
                }
                return
            }
            Log.w(TAG, "${op.label} was not accepted - skipping")
        }
    }

    companion object {
        const val OP_TIMEOUT_MS = 5_000L
    }
}
