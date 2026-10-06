package com.example.makamservis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import androidx.core.app.ServiceCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
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
        const val PREF_ROLE = "selected_role"
        const val PREF_FLOOR = "selected_floor"
        const val PREF_ROOM_SLUG = "selected_room_slug"
        const val PREF_ROOM_NAME = "selected_room_name"
        const val PREF_CONFIGURED = "is_role_configured"

        const val ROLE_KITCHEN = "kitchen"
        const val ROLE_ROOM = "room"
        const val ROLE_ADMIN = "admin"

        fun getFloorLabel(floorKey: String): String {
            return when (floorKey) {
                "makam" -> "⭐ Makam Katı Mutfağı"
                "kat-3" -> "🏢 3. Kat Mutfağı"
                "kat-4" -> "🏢 4. Kat Mutfağı"
                "kat-5" -> "🏢 5. Kat Mutfağı"
                "kat-6" -> "🏢 6. Kat Mutfağı"
                "all" -> "🌐 Tüm Mutfaklar (Merkezi)"
                else -> "$floorKey Mutfağı"
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var currentFloor: String = "makam"
    private val seenOrderIds = Collections.synchronizedSet(mutableSetOf<Int>())
    private var scheduler: ScheduledExecutorService? = null
    private var isConnected = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val role = prefs.getString(PREF_ROLE, ROLE_KITCHEN) ?: ROLE_KITCHEN
        if (role != ROLE_KITCHEN) {
            stopSelf()
            return
        }
        createNotificationChannels()
        currentFloor = prefs.getString(PREF_FLOOR, "makam") ?: "makam"
        
        safeStartForeground(createForegroundNotification("Mutfak Takibi Başlatılıyor..."))
        
        Thread {
            snapshotExistingOrders()
        }.start()

        startPollingAndWebSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        safeStartForeground(createForegroundNotification(if (isConnected) "🟢 Canlı Takip Aktif" else "🟡 Bağlantı Kuruluyor..."))

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CHANGE_FLOOR -> {
                val newFloor = intent.getStringExtra(EXTRA_FLOOR) ?: "makam"
                currentFloor = newFloor
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(PREF_FLOOR, newFloor)
                    .apply()
                Thread {
                    snapshotExistingOrders()
                }.start()
                restartConnection()
            }
            ACTION_TEST_NOTIFICATION -> {
                val floorLabel = getFloorLabel(currentFloor)
                triggerOrderAlert("Test Bildirimi ($floorLabel)", "1x Türk Kahvesi, 1x Su (Ses ve Titreşim Başarılı!)")
            }
            else -> {
                // Keep running
            }
        }
        return START_STICKY
    }

    private fun safeStartForeground(notification: android.app.Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    FOREGROUND_NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(FOREGROUND_NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "safeStartForeground error: ${e.message}", e)
            try {
                startForeground(FOREGROUND_NOTIF_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback startForeground error: ${e2.message}", e2)
            }
        }
    }

    private fun snapshotExistingOrders() {
        val url = "http://$SERVER_HOST/api/orders/active?floor=$currentFloor"
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val jsonArray = JSONArray(body)
                synchronized(seenOrderIds) {
                    seenOrderIds.clear()
                    for (i in 0 until jsonArray.length()) {
                        val order = jsonArray.getJSONObject(i)
                        seenOrderIds.add(order.getInt("id"))
                    }
                }
                Log.d(TAG, "Snapshot success: ${seenOrderIds.size} existing orders cached for floor $currentFloor")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error snapshotting orders: ${e.message}")
        }
    }

    private fun startPollingAndWebSocket() {
        connectWebSocket()

        scheduler?.shutdownNow()
        scheduler = Executors.newSingleThreadScheduledExecutor()
        // Poll every 6 seconds as a guaranteed backup in case of network drops
        scheduler?.scheduleWithFixedDelay({
            try {
                fetchAndCheckOrders()
            } catch (e: Exception) {
                Log.e(TAG, "Error polling orders", e)
            }
        }, 3, 6, TimeUnit.SECONDS)
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
                Log.d(TAG, "WebSocket message received: $text")
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
                // Reconnect after 4 seconds
                scheduler?.schedule({
                    connectWebSocket()
                }, 4, TimeUnit.SECONDS)
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

            for (i in 0 until jsonArray.length()) {
                val order = jsonArray.getJSONObject(i)
                val orderId = order.getInt("id")

                val orderFloor = order.optString("floor", "makam")
                if (currentFloor != "all" && orderFloor != currentFloor) {
                    continue
                }

                val isBrandNew: Boolean
                synchronized(seenOrderIds) {
                    if (!seenOrderIds.contains(orderId)) {
                        seenOrderIds.add(orderId)
                        isBrandNew = true
                    } else {
                        isBrandNew = false
                    }
                }

                if (isBrandNew) {
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
                    Log.d(TAG, ">>> YENI SIPARIS BULUNDU: #$orderId - $roomName: $itemsSummary")
                    triggerOrderAlert(roomName, itemsSummary)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch orders: ${e.message}")
        }
    }

    private fun triggerOrderAlert(roomName: String, itemsSummary: String) {
        // 1. Ekranı Aç (Kilitli veya kapalıysa uyandır)
        wakeUpScreen()

        // 2. Güçlü Titreşim Ver
        vibratePhone()

        // 3. Doğrudan Zil Sesini Çal (Hoparlörden ses çıkışı garantisi)
        playAlarmRingtone()

        // 4. Durum Çubuğuna Yüksek Öncelikli Pop-up Bildirim Çıkar
        showOrderNotification(roomName, itemsSummary)

        // 5. Activity'e anlık yayın gönder (ekran açıksa Toast & Yenileme için)
        val intent = Intent(BROADCAST_NEW_ORDER).apply {
            putExtra("room_name", roomName)
            putExtra("items_summary", itemsSummary)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun playAlarmRingtone() {
        try {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(applicationContext, soundUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.isLooping = false
            }
            ringtone.play()
        } catch (e: Exception) {
            Log.e(TAG, "Ringtone error", e)
        }
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
                description = "Uygulamanın arka planda canlı kaldığını gösterir"
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

        val floorLabel = getFloorLabel(currentFloor)

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Makam Servis Mutfak")
            .setContentText(if (isConnected) "🟢 $floorLabel ($statusText)" else "🟡 $floorLabel ($statusText)")
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
