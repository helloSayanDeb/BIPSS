package com.bipss.app.ble

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.bipss.app.notifications.BipssNotifications
import com.bipss.app.notifications.NotificationIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

private const val TAG = "BleForegroundService"

/**
 * Keeps the band connection alive while the app is backgrounded.
 *
 * Without this the GATT connection is torn down when the app leaves the
 * foreground - which is precisely what happens overnight, so sleep tracking
 * would never record anything. Android 13+ additionally requires a foreground
 * service of type connectedDevice to hold a BLE connection in the background
 * at all.
 *
 * The notification reflects the true connection state rather than always
 * claiming "Connected", so a user glancing at the shade is not told the band is
 * streaming when it dropped an hour ago.
 */
class BleForegroundService : Service() {

    private val bleGattManager: BleGattManager by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        BipssNotifications.createChannels(this)
        startForeground(
            NotificationIds.SERVICE,
            BipssNotifications.buildServiceNotification(this, connected = false)
        )
        observeConnection()
    }

    private fun observeConnection() {
        scope.launch {
            bleGattManager.connectionState.collectLatest { state ->
                val connected = state is BleConnectionState.Connected
                runCatching {
                    androidx.core.app.NotificationManagerCompat.from(this@BleForegroundService)
                        .notify(
                            NotificationIds.SERVICE,
                            BipssNotifications.buildServiceNotification(this@BleForegroundService, connected)
                        )
                }.onFailure { Log.w(TAG, "Could not update service notification: ${it.message}") }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, BleForegroundService::class.java)
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }.onFailure { Log.w(TAG, "Could not start foreground service: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BleForegroundService::class.java)) }
        }
    }
}
