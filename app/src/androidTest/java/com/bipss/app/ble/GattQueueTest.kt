package com.bipss.app.ble

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GattQueueTest {
    @Test fun rejectedRequestReportsFailureAndDoesNotBlockNext() = runBlocking {
        val failed = CompletableDeferred<Unit>()
        val next = CompletableDeferred<Unit>()
        val queue = GattOpQueue(this, 30)
        queue.enqueue(GattOpQueue.Op("rejected", onFailure = { failed.complete(Unit) }) { false })
        queue.enqueue(GattOpQueue.Op("next", awaitsCallback = false) { next.complete(Unit); true })
        withTimeout(1000) { failed.await(); next.await() }
        queue.clear()
    }
    @Test fun missingCallbackReportsFailureAndAdvances() = runBlocking {
        val failed = CompletableDeferred<Unit>()
        val next = CompletableDeferred<Unit>()
        val queue = GattOpQueue(this, 30)
        queue.enqueue(GattOpQueue.Op("timeout", onFailure = { failed.complete(Unit) }) { true })
        queue.enqueue(GattOpQueue.Op("next", awaitsCallback = false) { next.complete(Unit); true })
        withTimeout(1000) { failed.await(); next.await() }
        queue.clear()
    }
    @Test fun completedAndClearedRequestsNeverReportTimeout() = runBlocking {
        var failures = 0
        val queue = GattOpQueue(this, 30)
        queue.enqueue(GattOpQueue.Op("complete", onFailure = { failures++ }) { true })
        queue.complete()
        queue.enqueue(GattOpQueue.Op("clear", onFailure = { failures++ }) { true })
        queue.clear()
        delay(80)
        assertEquals(0, failures)
    }
}
