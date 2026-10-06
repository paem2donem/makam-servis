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
        const val ORDER_CHANNEL_ID = "makam_orders_channel_v6"
        const val FOREGROUND_NOTIF_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_CHANGE_FLOOR = "ACTION_CHANGE_FLOOR"
        const val ACTION_TEST_NOTIFICATION = "ACTION_TEST_NOTIFICATION"
        const val ACTION_TOGGLE_MUTE = "ACTION_TOGGLE_MUTE"
        const val EXTRA_FLOOR = "EXTRA_FLOOR"
        const val EXTRA_MUTED = "EXTRA_MUTED"

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
        const val PREF_IS_MUTED = "is_muted"

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
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var currentFloor: String = "makam"
    private var isMuted: Boolean = false
    private val seenOrderIds = Collections.synchronizedSet(mutableSetOf<Int>())
    private var scheduler: ScheduledExecutorService? = null
    private var isConnected = false
    private var serviceWakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val role = prefs.getString(PREF_ROLE, ROLE_KITCHEN) ?: ROLE_KITCHEN
        if (role != ROLE_KITCHEN) {
            stopSelf()
            return
        }

        // Arka planda CPU ve ağın uyumasını önlemek için Partial WakeLock tut
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            serviceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "makamservis:bg_service_wakelock")
            serviceWakeLock?.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "serviceWakeLock acquire error", e)
        }

        createNotificationChannels()
        currentFloor = prefs.getString(PREF_FLOOR, "makam") ?: "makam"
        isMuted = prefs.getBoolean(PREF_IS_MUTED, false)
        
        val initialStatus = if (isMuted) "🔇 Sesli Uyarı Kapalı" else "Mutfak Takibi Başlatılıyor..."
        safeStartForeground(createForegroundNotification(initialStatus))
        
        Thread {
            snapshotExistingOrders()
        }.start()

        startPollingAndWebSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val currentStatus = if (isMuted) "🔇 Sesli Uyarı Kapalı" else if (isConnected) "🟢 Sesli Uyarı Açık" else "🟡 Bağlantı Kuruluyor..."
        safeStartForeground(createForegroundNotification(currentStatus))

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> {
                isMuted = intent.getBooleanExtra(EXTRA_MUTED, false)
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean(PREF_IS_MUTED, isMuted)
                    .apply()
                updateForegroundNotification()
            }
            ACTION_CHANGE_FLOOR -> {
                val newFloor = intent.getStringExtra(EXTRA_FLOOR) ?: "makam"
                val floorChanged = currentFloor != newFloor
                currentFloor = newFloor
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(PREF_FLOOR, newFloor)
                    .apply()
                if (floorChanged) {
                    seenOrderIds.clear()
                    Thread {
                        snapshotExistingOrdersQuietly()
                    }.start()
                }
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
        try {
            fetchAndCheckOrders()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking orders on snapshot: ${e.message}")
        }
    }

    private fun snapshotExistingOrdersQuietly() {
        try {
            val url = "http://$SERVER_HOST/api/orders/active?floor=$currentFloor"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val jsonArray = JSONArray(body)
                synchronized(seenOrderIds) {
                    for (i in 0 until jsonArray.length()) {
                        val order = jsonArray.getJSONObject(i)
                        seenOrderIds.add(order.getInt("id"))
                    }
                }
                Log.d(TAG, "Quiet snapshot completed for $currentFloor with ${seenOrderIds.size} orders.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error snapshotting existing orders quietly: ${e.message}")
        }
    }

    private fun startPollingAndWebSocket() {
        connectWebSocket()

        scheduler?.shutdownNow()
        scheduler = Executors.newSingleThreadScheduledExecutor()
        // Her 3 saniyede bir kesintisiz yedek sorgu (WebSocket kopsa bile sipariş anında yakalanır)
        scheduler?.scheduleWithFixedDelay({
            try {
                fetchAndCheckOrders()
            } catch (t: Throwable) {
                Log.e(TAG, "Error polling orders", t)
            }
        }, 1, 3, TimeUnit.SECONDS)
    }

    private fun restartConnection() {
        try {
            val oldWs = webSocket
            webSocket = null
            oldWs?.cancel()
        } catch (_: Exception) {}
        connectWebSocket()
        updateForegroundNotification()
    }

    private fun connectWebSocket() {
        val targetFloor = currentFloor
        val url = if (targetFloor == "all") {
            "ws://$SERVER_HOST/ws/mutfak"
        } else {
            "ws://$SERVER_HOST/ws/mutfak/$targetFloor"
        }

        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (ws != webSocket) return
                Log.d(TAG, "WebSocket Connected to $url")
                isConnected = true
                broadcastStatus(true)
                updateForegroundNotification()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (ws != webSocket) return
                Log.d(TAG, "WebSocket message received: $text")
                if (text == "new_order") {
                    fetchAndCheckOrders()
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                if (ws != webSocket) return
                isConnected = false
                broadcastStatus(false)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (ws != webSocket) return
                Log.e(TAG, "WebSocket Failure: ${t.message}")
                isConnected = false
                broadcastStatus(false)
                updateForegroundNotification()
                // Reconnect after 3 seconds
                scheduler?.schedule({
                    connectWebSocket()
                }, 3, TimeUnit.SECONDS)
            }
        }
        webSocket = client.newWebSocket(request, listener)
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
            val response = httpClient.newCall(request).execute()
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
                    triggerOrderAlert(roomName, itemsSummary, orderId)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to fetch orders: ${t.message}")
        }
    }

    private fun triggerOrderAlert(roomName: String, itemsSummary: String, orderId: Int = 0) {
        if (isMuted) {
            Log.d(TAG, "Sesli uyarı kapalı: Ses ve titreşim engellendi ($roomName).")
            val intent = Intent(BROADCAST_NEW_ORDER).apply {
                putExtra("order_id", orderId)
                putExtra("room_name", roomName)
                putExtra("items_summary", itemsSummary)
                putExtra("is_muted", true)
                setPackage(packageName)
            }
            sendBroadcast(intent)
            return
        }

        // 1. Ekranı Aç (Kilitli veya kapalıysa uyandır)
        wakeUpScreen()

        // 2. Güçlü Titreşim Ver (Alarm düzeyinde)
        vibratePhone()

        // 3. Doğrudan Zil Sesini ve Alarm Tonunu Çal (Hoparlörden garanti çıkış)
        playAlarmRingtone()

        // 4. Durum Çubuğuna Yüksek Öncelikli Pop-up Bildirim Çıkar
        showOrderNotification(roomName, itemsSummary)

        // 5. Activity'e anlık yayın gönder (ekran açıksa Pop-up & Yenileme için)
        val intent = Intent(BROADCAST_NEW_ORDER).apply {
            putExtra("order_id", orderId)
            putExtra("room_name", roomName)
            putExtra("items_summary", itemsSummary)
            putExtra("is_muted", false)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private var activeServiceRingtone: android.media.Ringtone? = null

    private fun playAlarmRingtone() {
        try {
            activeServiceRingtone?.stop()
            activeServiceRingtone = null
        } catch (_: Exception) {}

        // Standart Kısa Bildirim Zil Sesi (Notification Chime)
        try {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(applicationContext, soundUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.isLooping = false
            }
            ringtone.play()
            activeServiceRingtone = ringtone

            // En fazla 2 saniye sonra sesi zorla kes (sürekli çalmasını engeller)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    activeServiceRingtone?.stop()
                    activeServiceRingtone = null
                } catch (_: Exception) {}
            }, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "Ringtone error", e)
        }
    }

    private fun wakeUpScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "makamservis:order_alert"
            )
            wakeLock.acquire(3000)
        } catch (e: Exception) {
            Log.e(TAG, "Wakelock error", e)
        }
    }

    private fun vibratePhone() {
        try {
            // Kısa, tatlı 2 darbeli bildirim titreşimi (~0.85 sn)
            val pattern = longArrayOf(0, 350, 150, 350)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createWaveform(pattern, -1),
                    audioAttributes
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1), audioAttributes)
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
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        val notif = NotificationCompat.Builder(this, ORDER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🛎️ YENİ SİPARİŞ: $roomName")
            .setContentText(itemsSummary)
            .setStyle(NotificationCompat.BigTextStyle().bigText("Oda: $roomName\nSiparişler: $itemsSummary"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
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
                description = "Gelen yeni ikram siparişleri için sesli bildirim ve titreşim"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 350, 150, 350)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
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
        val displayText = if (isMuted) {
            "🔇 $floorLabel (Sesli Uyarı Kapalı)"
        } else if (isConnected) {
            "🟢 $floorLabel (Sesli Uyarı Açık)"
        } else {
            "🟡 $floorLabel ($statusText)"
        }

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Makam Servis Mutfak")
            .setContentText(displayText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = if (isMuted) "Sesli Uyarı Kapalı" else if (isConnected) "Sesli Uyarı Açık" else "Bağlanıyor"
        manager.notify(FOREGROUND_NOTIF_ID, createForegroundNotification(text))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved: Ensuring background notification service remains active")
        try {
            val restartIntent = Intent(applicationContext, OrderNotificationService::class.java).apply {
                setPackage(packageName)
                action = ACTION_START
            }
            val pendingIntent = PendingIntent.getService(
                applicationContext,
                1001,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            alarmManager?.set(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 1000,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in onTaskRemoved", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (serviceWakeLock?.isHeld == true) {
                serviceWakeLock?.release()
            }
        } catch (_: Exception) {}
        serviceWakeLock = null
        try {
            webSocket?.close(1000, "Service destroyed")
        } catch (_: Exception) {}
        scheduler?.shutdownNow()
    }
}
