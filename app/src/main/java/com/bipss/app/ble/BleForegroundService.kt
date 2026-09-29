package com.bipss.app.ble

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.bipss.app.data.environmental.AqiContextRepository
import com.bipss.app.notifications.BipssNotifications
import com.bipss.app.notifications.NotificationIds
import com.bipss.app.sos.LocationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
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
 * While the band is connected it also takes the indoor/outdoor check every
 * 10 minutes (AqiContextRepository). That needs the location service type,
 * which Android grants only to a service started or refreshed while the app
 * is in the foreground and holding precise location - so the type is chosen
 * at runtime, re-applied whenever the app comes back to the foreground, and a
 * refusal falls back to connectedDevice alone (no checks, never a crash).
 *
 * The notification reflects the true connection state rather than always
 * claiming "Connected", so a user glancing at the shade is not told the band is
 * streaming when it dropped an hour ago.
 */
class BleForegroundService : Service() {

    private val bleGattManager: BleGattManager by inject()
    private val locationHelper: LocationHelper by inject()
    private val aqiContext: AqiContextRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Whether the service currently runs with the location type, i.e. may use GNSS in the background. */
    @Volatile private var locationTypeActive = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        BipssNotifications.createChannels(this)
        if (!startForegroundWithTypes()) {
            stopSelf()
            return
        }
        observeConnection()
        sampleContextWhileConnected()
    }

    /**
     * connectedDevice always; location too when precise location is granted
     * and the app is in the foreground. Returns false when even the base type
     * was refused (the service cannot run).
     */
    private fun startForegroundWithTypes(): Boolean {
        val notification = BipssNotifications.buildServiceNotification(
            this, connected = bleGattManager.connectionState.value is BleConnectionState.Connected
        )
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val wantLocation = locationHelper.hasFinePermission() && appInForeground()
        if (wantLocation) {
            try {
                ServiceCompat.startForeground(this, NotificationIds.SERVICE, notification,
                    base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                locationTypeActive = true
                return true
            } catch (e: Exception) {
                // SecurityException, or ForegroundServiceStartNotAllowedException on 12+.
                Log.w(TAG, "Location service type refused, continuing without it: ${e.message}")
            }
        }
        locationTypeActive = false
        return try {
            ServiceCompat.startForeground(this, NotificationIds.SERVICE, notification, base)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Foreground service refused: ${e.message}")
            false
        }
    }

    private fun appInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun observeConnection() {
        scope.launch {
            bleGattManager.connectionState.collectLatest { state ->
                val connected = state is BleConnectionState.Connected
                // Through BipssNotifications so a missing POST_NOTIFICATIONS grant
                // is checked and logged rather than thrown.
                BipssNotifications.updateServiceNotification(this@BleForegroundService, connected)
            }
        }
    }

    /**
     * One indoor/outdoor check shortly after the band connects, then every
     * [SAMPLE_INTERVAL_MS] until it disconnects. Skipped whenever GNSS cannot
     * be used right now: a receiver the system keeps quiet in the background
     * must not be read as "indoors".
     */
    private fun sampleContextWhileConnected() {
        scope.launch {
            bleGattManager.connectionState
                .map { it is BleConnectionState.Connected }
                .distinctUntilChanged()
                .collectLatest { connected ->
                    if (!connected) return@collectLatest
                    delay(FIRST_SAMPLE_DELAY_MS)
                    while (isActive) {
                        if (locationTypeActive || appInForeground()) {
                            runCatching { aqiContext.sampleNow() }
                                .onFailure { Log.w(TAG, "Context check failed: ${it.message}") }
                        }
                        delay(SAMPLE_INTERVAL_MS)
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_TYPES) startForegroundWithTypes()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_REFRESH_TYPES = "com.bipss.app.ble.REFRESH_TYPES"
        const val SAMPLE_INTERVAL_MS = 10 * 60_000L
        private const val FIRST_SAMPLE_DELAY_MS = 30_000L

        @Volatile var isRunning = false
            private set

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, BleForegroundService::class.java)
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }.onFailure { Log.w(TAG, "Could not start foreground service: ${it.message}") }
        }

        /**
         * Called when the app comes to the foreground: the one moment Android
         * lets the service take the location type. Only when it is running -
         * this must never start the band service by itself.
         */
        fun refreshForegroundTypes(context: Context) {
            if (!isRunning) return
            runCatching {
                context.startService(Intent(context, BleForegroundService::class.java).setAction(ACTION_REFRESH_TYPES))
            }.onFailure { Log.w(TAG, "Could not refresh service type: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BleForegroundService::class.java)) }
        }
    }
}
