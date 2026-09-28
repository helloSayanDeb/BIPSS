package com.bipss.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/**
 * Wall-clock time that re-emits every [periodMs], so relative ages on screen
 * ("updated 3m ago") keep counting instead of freezing at the value they had
 * when the data loaded.
 */
@Composable
fun rememberNowMs(periodMs: Long = 30_000L): Long {
    val now by produceState(System.currentTimeMillis(), periodMs) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}
