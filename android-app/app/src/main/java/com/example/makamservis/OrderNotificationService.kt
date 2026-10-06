package com.example.makamservis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class OrderNotificationService : Service() {

    companion object {
        const val TAG = "OrderNotifService"
        const val FOREGROUND_CHANNEL_ID = "makam_fg_channel"
        const val ORDER_CHANNEL_ID = "makam_orders_channel_high"
        const val FOREGROUND_NOTIF_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_CHANGE_FLOOR = "ACTION_CHANGE_FLOOR"
        const val ACTION_TEST_NOTIFICATION = "ACTION_TEST_NOTIFICATION"
        const val EXTRA_FLOOR = "EXTRA_FLOOR"

        const val BROADCAST_NEW_ORDER = "com.example.makamservis.NEW_ORDER"
        const val BROADCAST_STATUS_CHANGE = "com.example.makamservis.STATUS_CHANGE"
        const val EXTRA_STATUS = "EXTRA_STATUS"

        const val SERVER_HOST = "152.70.188.116"
        const val PREFS_NAME = "makam_prefs"
        const val PREF_FLOOR = "selected_floor"
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var currentFloor: String = "makam"
    private val seenOrderIds = mutableSetOf<Int>()
    private var isFirstFetch = true
    private var scheduler: ScheduledExecutorService? = null
    private var isConnected = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(FOREGROUND_NOTIF_ID, createForegroundNotification("Başlatılıyor..."))

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentFloor = prefs.getString(PREF_FLOOR, "makam") ?: "makam"

        startPollingAndWebSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CHANGE_FLOOR -> {
                val newFloor = intent.getStringExtra(EXTRA_FLOOR) ?: "makam"
                if (newFloor != currentFloor) {
                    currentFloor = newFloor
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putString(PREF_FLOOR, newFloor)
                        .apply()
                    seenOrderIds.clear()
                    isFirstFetch = true
                    restartConnection()
                }
            }
            ACTION_TEST_NOTIFICATION -> {
                triggerOrderAlert("Test Odası", "1x Türk Kahvesi, 1x Su (Test Bildirimi)")
            }
            else -> {
                // Default start or restart
            }
        }
        return START_STICKY
    }

    private fun startPollingAndWebSocket() {
        connectWebSocket()

        scheduler?.shutdownNow()
        scheduler = Executors.newSingleThreadScheduledExecutor()
        // Poll every 8 seconds as robust backup
        scheduler?.scheduleWithFixedDelay({
            try {
                fetchAndCheckOrders()
            } catch (e: Exception) {
                Log.e(TAG, "Error polling orders", e)
            }
        }, 3, 8, TimeUnit.SECONDS)
    }

    private fun restartConnection() {
        try {
            webSocket?.close(1000, "Floor change")
        } catch (_: Exception) {}
        connectWebSocket()
        updateForegroundNotification()
    }

    private fun connectWebSocket() {
        val url = if (currentFloor == "all") {
            "ws://$SERVER_HOST/ws/mutfak"
        } else {
            "ws://$SERVER_HOST/ws/mutfak/$currentFloor"
        }

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket Connected to $url")
                isConnected = true
                broadcastStatus(true)
                updateForegroundNotification()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "WebSocket message: $text")
                if (text == "new_order") {
                    fetchAndCheckOrders()
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                broadcastStatus(false)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Failure: ${t.message}")
                isConnected = false
                broadcastStatus(false)
                updateForegroundNotification()
                // Reconnect after 5 seconds
                scheduler?.schedule({
                    connectWebSocket()
                }, 5, TimeUnit.SECONDS)
            }
        })
    }

    private fun broadcastStatus(connected: Boolean) {
        val intent = Intent(BROADCAST_STATUS_CHANGE).apply {
            putExtra(EXTRA_STATUS, connected)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun fetchAndCheckOrders() {
        val url = "http://$SERVER_HOST/api/orders/active?floor=$currentFloor"
        val request = Request.Builder().url(url).build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return
            val body = response.body?.string() ?: return
            val jsonArray = JSONArray(body)

            val currentOrderIds = mutableSetOf<Int>()
            for (i in 0 until jsonArray.length()) {
                val order = jsonArray.getJSONObject(i)
                val orderId = order.getInt("id")
                currentOrderIds.add(orderId)

                // If this is a new order we haven't alerted for
                if (!seenOrderIds.contains(orderId)) {
                    seenOrderIds.add(orderId)
                    if (!isFirstFetch) {
                        val roomName = order.optString("room_name", "Oda")
                        val itemsArray = order.optJSONArray("items") ?: JSONArray()
                        val itemsSummary = buildString {
                            for (j in 0 until itemsArray.length()) {
                                val item = itemsArray.getJSONObject(j)
                                val q = item.optInt("quantity", 1)
                                val pName = item.optString("product_name", "Ürün")
                                val notes = item.optString("notes", "")
                                if (j > 0) append(", ")
                                append("${q}x $pName")
                                if (notes.isNotBlank()) append(" ($notes)")
                            }
                        }
                        triggerOrderAlert(roomName, itemsSummary)
                    }
                }
            }
            isFirstFetch = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch orders", e)
        }
    }

    private fun triggerOrderAlert(roomName: String, itemsSummary: String) {
        // 1. Wake screen
        wakeUpScreen()

        // 2. Vibrate
        vibratePhone()

        // 3. Sound & Notification
        showOrderNotification(roomName, itemsSummary)

        // 4. Broadcast to Activity
        val intent = Intent(BROADCAST_NEW_ORDER).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun wakeUpScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "makamservis:order_alert"
            )
            wakeLock.acquire(4000)
        } catch (e: Exception) {
            Log.e(TAG, "Wakelock error", e)
        }
    }

    private fun vibratePhone() {
        try {
            val pattern = longArrayOf(0, 600, 250, 600, 250, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate error", e)
        }
    }

    private fun showOrderNotification(roomName: String, itemsSummary: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        val notif = NotificationCompat.Builder(this, ORDER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🛎️ YENİ SİPARİŞ: $roomName")
            .setContentText(itemsSummary)
            .setStyle(NotificationCompat.BigTextStyle().bigText("Oda: $roomName\nSiparişler: $itemsSummary"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSound(soundUri)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify((System.currentTimeMillis() % 100000).toInt(), notif)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Foreground persistent channel
            val fgChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Makam Servis Durumu",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Uygulamanın arka planda aktif kaldığını gösterir"
                setShowBadge(false)
            }
            manager.createNotificationChannel(fgChannel)

            // High priority Order channel
            val orderChannel = NotificationChannel(
                ORDER_CHANNEL_ID,
                "Yeni İkram Siparişleri",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Gelen yeni ikram siparişleri için sesli ve titreşimli acil uyarı"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 250, 600, 250, 800)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(orderChannel)
        }
    }

    private fun createForegroundNotification(statusText: String): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val floorLabel = when (currentFloor) {
            "makam" -> "⭐ Makam Katı"
            "kat-3" -> "🏢 3. Kat"
            "kat-4" -> "🏢 4. Kat"
            "kat-5" -> "🏢 5. Kat"
            "kat-6" -> "🏢 6. Kat"
            else -> "🌐 Tüm Katlar"
        }

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Makam Servis Mutfak ($floorLabel)")
            .setContentText(if (isConnected) "🟢 Canlı Bağlantı - Siparişler dinleniyor" else "🟡 Bağlantı kuruluyor...")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(FOREGROUND_NOTIF_ID, createForegroundNotification(if (isConnected) "Canlı" else "Bağlanıyor"))
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            webSocket?.close(1000, "Service destroyed")
        } catch (_: Exception) {}
        scheduler?.shutdownNow()
    }
}
