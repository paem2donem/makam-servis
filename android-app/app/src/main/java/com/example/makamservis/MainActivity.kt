package com.example.makamservis

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class RoomItem(
    val id: Int,
    val name: String,
    val slug: String,
    val floor: String
)

data class NewOrderPopupData(
    val id: Int,
    val roomName: String,
    val itemsSummary: String
)

class MainActivity : ComponentActivity() {

    private var webViewRef: WebView? = null
    private var isConnectedState = mutableStateOf(false)
    private var isMutedState = mutableStateOf(false)
    private var selectedRoleState = mutableStateOf(OrderNotificationService.ROLE_KITCHEN)
    private var selectedFloorState = mutableStateOf("makam")
    private var selectedRoomSlugState = mutableStateOf("")
    private var selectedRoomNameState = mutableStateOf("")
    private var showRoleDialogState = mutableStateOf(false)
    private var showRoomPickerState = mutableStateOf(false)
    private var availableRoomsState = mutableStateOf<List<RoomItem>>(emptyList())
    private var newOrderAlertState = mutableStateOf<NewOrderPopupData?>(null)

    private var activityScheduler: ScheduledExecutorService? = null
    private val foregroundSeenOrderIds = Collections.synchronizedSet(mutableSetOf<Int>())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                OrderNotificationService.BROADCAST_NEW_ORDER -> {
                    val room = intent.getStringExtra("room_name") ?: "Yeni Sipariş"
                    val items = intent.getStringExtra("items_summary") ?: ""
                    val isMuted = intent.getBooleanExtra("is_muted", false)
                    val orderId = intent.getIntExtra("order_id", 0)
                    if (isMuted) {
                        Toast.makeText(this@MainActivity, "🔇 (Sesli Uyarı Kapalı) $room yeni sipariş verdi", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "🛎️ $room: $items", Toast.LENGTH_LONG).show()
                        playInAppLoudAlert()
                        newOrderAlertState.value = NewOrderPopupData(orderId, room, items)
                    }
                    webViewRef?.reload()
                }
                OrderNotificationService.BROADCAST_STATUS_CHANGE -> {
                    val status = intent.getBooleanExtra(OrderNotificationService.EXTRA_STATUS, false)
                    isConnectedState.value = status
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Bildirim izni verildi!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bildirim izni verilmedi; sesli uyarılar çalışmayabilir.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkNotificationPermission()
        checkBatteryOptimization()
        loadPreferences()
        fetchRoomsList()

        val filter = IntentFilter().apply {
            addAction(OrderNotificationService.BROADCAST_NEW_ORDER)
            addAction(OrderNotificationService.BROADCAST_STATUS_CHANGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        // Handle Deep Linking (e.g. scanned QR code opening the app)
        handleDeepLink(intent)

        // Ensure service runs only if role is Kitchen
        syncServiceWithRole()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webViewRef?.canGoBack() == true) {
                    webViewRef?.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        setContent {
            MaterialTheme {
                AppScreen(
                    role = selectedRoleState.value,
                    floor = selectedFloorState.value,
                    roomSlug = selectedRoomSlugState.value,
                    roomName = selectedRoomNameState.value,
                    isConnected = isConnectedState.value,
                    showRoleDialog = showRoleDialogState.value,
                    showRoomPicker = showRoomPickerState.value,
                    availableRooms = availableRoomsState.value,
                    newOrderAlert = newOrderAlertState.value,
                    onDismissNewOrderAlert = {
                        stopAlertSound()
                        newOrderAlertState.value = null
                        webViewRef?.reload()
                    },
                    onOpenRoleDialog = { showRoleDialogState.value = true },
                    onDismissRoleDialog = { showRoleDialogState.value = false },
                    onOpenRoomPicker = { showRoomPickerState.value = true },
                    onDismissRoomPicker = { showRoomPickerState.value = false },
                    onApplyRole = { newRole, newFloor, newSlug, newName ->
                        applyRoleConfiguration(newRole, newFloor, newSlug, newName)
                        showRoleDialogState.value = false
                    },
                    onSelectRoom = { room ->
                        selectRoomAndNavigate(room)
                        showRoomPickerState.value = false
                    },
                    onTestAlert = { testAlert() },
                    isMuted = isMutedState.value,
                    onToggleMute = { toggleMute(!isMutedState.value) },
                    onRefresh = { webViewRef?.reload() },
                    onWebViewCreated = { webView ->
                        webViewRef = webView
                        loadInitialUrl()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val path = uri.path?.removePrefix("/") ?: ""
        if (path.isNotEmpty() && !path.startsWith("mutfak") && !path.startsWith("admin") &&
            !path.startsWith("api") && !path.startsWith("static") && !path.startsWith("indir") && !path.startsWith("app")) {
            // QR scanned or room link clicked!
            selectedRoleState.value = OrderNotificationService.ROLE_ROOM
            selectedRoomSlugState.value = path
            selectedRoomNameState.value = path

            val prefs = getSharedPreferences(OrderNotificationService.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(OrderNotificationService.PREF_ROLE, OrderNotificationService.ROLE_ROOM)
                .putString(OrderNotificationService.PREF_ROOM_SLUG, path)
                .putBoolean(OrderNotificationService.PREF_CONFIGURED, true)
                .apply()

            syncServiceWithRole()
            webViewRef?.loadUrl("http://${OrderNotificationService.SERVER_HOST}/$path")
            Toast.makeText(this, "Oda menüsü açıldı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(OrderNotificationService.PREFS_NAME, Context.MODE_PRIVATE)
        val isConfigured = prefs.getBoolean(OrderNotificationService.PREF_CONFIGURED, false)
        selectedRoleState.value = prefs.getString(OrderNotificationService.PREF_ROLE, OrderNotificationService.ROLE_KITCHEN) ?: OrderNotificationService.ROLE_KITCHEN
        selectedFloorState.value = prefs.getString(OrderNotificationService.PREF_FLOOR, "makam") ?: "makam"
        selectedRoomSlugState.value = prefs.getString(OrderNotificationService.PREF_ROOM_SLUG, "") ?: ""
        selectedRoomNameState.value = prefs.getString(OrderNotificationService.PREF_ROOM_NAME, "") ?: ""
        isMutedState.value = prefs.getBoolean(OrderNotificationService.PREF_IS_MUTED, false)

        if (!isConfigured) {
            showRoleDialogState.value = true
        }
    }

    private fun toggleMute(muted: Boolean) {
        isMutedState.value = muted
        val prefs = getSharedPreferences(OrderNotificationService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(OrderNotificationService.PREF_IS_MUTED, muted).apply()

        val intent = Intent(this, OrderNotificationService::class.java).apply {
            action = OrderNotificationService.ACTION_TOGGLE_MUTE
            putExtra(OrderNotificationService.EXTRA_MUTED, muted)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error sending mute action to service", e)
        }

        if (muted) {
            Toast.makeText(this, "🔇 Sesli Uyarı Kapatıldı (Telefon çalmaz)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "🔊 Sesli Uyarı Açıldı (Telefon sesli çalar)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadInitialUrl() {
        val role = selectedRoleState.value
        val floor = selectedFloorState.value
        val slug = selectedRoomSlugState.value

        val url = when (role) {
            OrderNotificationService.ROLE_KITCHEN -> "http://${OrderNotificationService.SERVER_HOST}/mutfak/$floor"
            OrderNotificationService.ROLE_ROOM -> {
                if (slug.isNotBlank()) "http://${OrderNotificationService.SERVER_HOST}/$slug"
                else "http://${OrderNotificationService.SERVER_HOST}/"
            }
            OrderNotificationService.ROLE_ADMIN -> "http://${OrderNotificationService.SERVER_HOST}/admin"
            else -> "http://${OrderNotificationService.SERVER_HOST}/mutfak/$floor"
        }
        webViewRef?.loadUrl(url)
    }

    private fun applyRoleConfiguration(role: String, floor: String, slug: String, name: String) {
        selectedRoleState.value = role
        selectedFloorState.value = floor
        selectedRoomSlugState.value = slug
        selectedRoomNameState.value = name

        foregroundSeenOrderIds.clear()

        val prefs = getSharedPreferences(OrderNotificationService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(OrderNotificationService.PREF_ROLE, role)
            .putString(OrderNotificationService.PREF_FLOOR, floor)
            .putString(OrderNotificationService.PREF_ROOM_SLUG, slug)
            .putString(OrderNotificationService.PREF_ROOM_NAME, name)
            .putBoolean(OrderNotificationService.PREF_CONFIGURED, true)
            .apply()

        syncServiceWithRole()
        loadInitialUrl()
        Toast.makeText(this, "Rol ayarlandı: ${getRoleDisplayName(role)}", Toast.LENGTH_SHORT).show()
    }

    private fun selectRoomAndNavigate(room: RoomItem) {
        selectedRoleState.value = OrderNotificationService.ROLE_ROOM
        selectedRoomSlugState.value = room.slug
        selectedRoomNameState.value = room.name

        val prefs = getSharedPreferences(OrderNotificationService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(OrderNotificationService.PREF_ROLE, OrderNotificationService.ROLE_ROOM)
            .putString(OrderNotificationService.PREF_ROOM_SLUG, room.slug)
            .putString(OrderNotificationService.PREF_ROOM_NAME, room.name)
            .putBoolean(OrderNotificationService.PREF_CONFIGURED, true)
            .apply()

        syncServiceWithRole()
        webViewRef?.loadUrl("http://${OrderNotificationService.SERVER_HOST}/${room.slug}")
        Toast.makeText(this, "${room.name} seçildi", Toast.LENGTH_SHORT).show()
    }

    private fun syncServiceWithRole() {
        if (selectedRoleState.value == OrderNotificationService.ROLE_KITCHEN) {
            val intent = Intent(this, OrderNotificationService::class.java).apply {
                action = OrderNotificationService.ACTION_CHANGE_FLOOR
                putExtra(OrderNotificationService.EXTRA_FLOOR, selectedFloorState.value)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            startForegroundOrderChecker()
        } else {
            stopForegroundOrderChecker()
            // Stop loud alarm service for Room or Admin users
            val intent = Intent(this, OrderNotificationService::class.java).apply {
                action = OrderNotificationService.ACTION_STOP
            }
            startService(intent)
        }
    }

    private fun fetchRoomsList() {
        Thread {
            try {
                val client = OkHttpClient()
                val req = Request.Builder().url("http://${OrderNotificationService.SERVER_HOST}/api/rooms").build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val array = JSONArray(body)
                    val list = mutableListOf<RoomItem>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        list.add(
                            RoomItem(
                                id = obj.getInt("id"),
                                name = obj.getString("name"),
                                slug = obj.getString("slug"),
                                floor = obj.optString("floor", "makam")
                            )
                        )
                    }
                    runOnUiThread {
                        availableRoomsState.value = list
                        if (selectedRoomSlugState.value.isNotBlank() && selectedRoomNameState.value.isBlank()) {
                            val match = list.firstOrNull { it.slug == selectedRoomSlugState.value }
                            if (match != null) {
                                selectedRoomNameState.value = match.name
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to fetch rooms", e)
            }
        }.start()
    }

    private var activeInAppRingtone: android.media.Ringtone? = null

    private fun stopAlertSound() {
        try {
            activeInAppRingtone?.stop()
            activeInAppRingtone = null
        } catch (_: Exception) {}
    }

    private fun playInAppLoudAlert() {
        if (isMutedState.value) return
        stopAlertSound()

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "makamservis:inapp_alert"
            )
            wl.acquire(3000)
        } catch (e: Exception) {
            Log.e("MainActivity", "Wakelock error", e)
        }

        // 1. Kısa bildirim zil sesi (Notification Chime)
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
            activeInAppRingtone = ringtone

            // En fazla 2 saniye sonra sesi zorla kes (sürekli çalmasını engeller)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                stopAlertSound()
            }, 2000)
        } catch (e: Exception) {
            Log.e("MainActivity", "Ringtone error", e)
        }

        // 2. Kısa, tatlı 2 darbeli bildirim titreşimi (~0.85 sn)
        try {
            val pattern = longArrayOf(0, 350, 150, 350)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1), audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createWaveform(pattern, -1), audioAttributes)
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Vibrator error", e)
        }
    }

    private fun startForegroundOrderChecker() {
        stopForegroundOrderChecker()
        if (selectedRoleState.value != OrderNotificationService.ROLE_KITCHEN) return

        activityScheduler = Executors.newSingleThreadScheduledExecutor()
        activityScheduler?.scheduleWithFixedDelay({
            try {
                if (selectedRoleState.value != OrderNotificationService.ROLE_KITCHEN) return@scheduleWithFixedDelay
                val floor = selectedFloorState.value
                val url = "http://${OrderNotificationService.SERVER_HOST}/api/orders/active?floor=$floor"
                val request = Request.Builder().url(url).build()
                val response = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build().newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val jsonArray = JSONArray(body)
                    for (i in 0 until jsonArray.length()) {
                        val order = jsonArray.getJSONObject(i)
                        val orderId = order.getInt("id")
                        if (!foregroundSeenOrderIds.contains(orderId)) {
                            foregroundSeenOrderIds.add(orderId)
                            val roomName = order.optString("room_name", "Bilinmeyen Oda")
                            val itemsSummary = order.optString("items_summary", "Yeni Sipariş")
                            runOnUiThread {
                                if (!isMutedState.value) {
                                    playInAppLoudAlert()
                                }
                                newOrderAlertState.value = NewOrderPopupData(orderId, roomName, itemsSummary)
                                webViewRef?.reload()
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.d("MainActivity", "Foreground check: ${t.message}")
            }
        }, 1, 2500, TimeUnit.MILLISECONDS)
    }

    private fun stopForegroundOrderChecker() {
        activityScheduler?.shutdownNow()
        activityScheduler = null
    }

    private fun testAlert() {
        playInAppLoudAlert()
        newOrderAlertState.value = NewOrderPopupData(
            id = 9999,
            roomName = "🔔 Test Odası",
            itemsSummary = "1x Çay, 1x Su (Ses ve titreşim testi başarılı)"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        val intent = Intent(this, OrderNotificationService::class.java).apply {
            action = OrderNotificationService.ACTION_TEST_NOTIFICATION
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "🔔 Ses ve titreşim testi çalındı!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Test alert error", e)
            Toast.makeText(this, "Hata: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Battery optimization intent error", e)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startForegroundOrderChecker()
    }

    override fun onPause() {
        super.onPause()
        stopAlertSound()
        stopForegroundOrderChecker()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlertSound()
        stopForegroundOrderChecker()
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {}
    }

    private fun getRoleDisplayName(role: String): String {
        return when (role) {
            OrderNotificationService.ROLE_KITCHEN -> "Mutfak Görevlisi"
            OrderNotificationService.ROLE_ROOM -> "Oda / Sipariş Veren"
            OrderNotificationService.ROLE_ADMIN -> "Yönetici"
            else -> "Mutfak Görevlisi"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    role: String,
    floor: String,
    roomSlug: String,
    roomName: String,
    isConnected: Boolean,
    showRoleDialog: Boolean,
    showRoomPicker: Boolean,
    availableRooms: List<RoomItem>,
    newOrderAlert: NewOrderPopupData?,
    onDismissNewOrderAlert: () -> Unit,
    onOpenRoleDialog: () -> Unit,
    onDismissRoleDialog: () -> Unit,
    onOpenRoomPicker: () -> Unit,
    onDismissRoomPicker: () -> Unit,
    onApplyRole: (role: String, floor: String, slug: String, name: String) -> Unit,
    onSelectRoom: (RoomItem) -> Unit,
    onTestAlert: () -> Unit,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onRefresh: () -> Unit,
    onWebViewCreated: (WebView) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val titleText = when (role) {
                                OrderNotificationService.ROLE_KITCHEN -> OrderNotificationService.getFloorLabel(floor)
                                OrderNotificationService.ROLE_ROOM -> if (roomName.isNotBlank()) "🚪 $roomName" else "🏛️ Makam Servis"
                                OrderNotificationService.ROLE_ADMIN -> "⚙️ Yönetici Paneli"
                                else -> "Makam Servis"
                            }

                            Text(
                                text = titleText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color.White,
                                maxLines = 1
                            )

                            if (role == OrderNotificationService.ROLE_KITCHEN) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isConnected) Color(0xFF10B981) else Color(0xFFF59E0B)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isConnected) "Canlı" else "Bağlanıyor",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            text = when (role) {
                                OrderNotificationService.ROLE_KITCHEN -> "Kat siparişleri takip ediliyor"
                                OrderNotificationService.ROLE_ROOM -> "Sipariş verme ekranı"
                                OrderNotificationService.ROLE_ADMIN -> "Sistem yönetim ekranı"
                                else -> ""
                            },
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                },
                actions = {
                    // Quick Room Picker if in Room mode
                    if (role == OrderNotificationService.ROLE_ROOM) {
                        Surface(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clickable { onOpenRoomPicker() },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF2563EB)
                        ) {
                            Text(
                                text = "🚪 Oda",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Tek ve Net Sesli Uyarı Butonu (Açık / Kapalı)
                    if (role == OrderNotificationService.ROLE_KITCHEN) {
                        Surface(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .clickable { onToggleMute() },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isMuted) Color(0xFFDC2626) else Color(0xFF16A34A)
                        ) {
                            Text(
                                text = if (isMuted) "🔇 Sesli Uyarı: Kapalı" else "🔊 Sesli Uyarı: Açık",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)
                            )
                        }
                    }

                    // Test Sound Button for Kitchen
                    if (role == OrderNotificationService.ROLE_KITCHEN) {
                        IconButton(
                            onClick = onTestAlert,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text(text = "🔔", fontSize = 16.sp)
                        }
                    }

                    // Role / Floor Switch Button
                    Surface(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .clickable { onOpenRoleDialog() },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF334155)
                    ) {
                        Text(
                            text = "🔄 Rol",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Reload Button
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text(text = "🔃", fontSize = 16.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            mediaPlaybackRequiresUserGesture = false
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                if (url != null) {
                                    view?.loadUrl(url)
                                }
                                return true
                            }
                        }
                        webChromeClient = WebChromeClient()
                        onWebViewCreated(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

    if (showRoleDialog) {
        RoleSelectionDialog(
            initialRole = role,
            initialFloor = floor,
            initialSlug = roomSlug,
            initialName = roomName,
            availableRooms = availableRooms,
            onDismiss = onDismissRoleDialog,
            onApply = onApplyRole
        )
    }

    if (showRoomPicker) {
        RoomPickerDialog(
            availableRooms = availableRooms,
            currentSlug = roomSlug,
            onDismiss = onDismissRoomPicker,
            onSelect = onSelectRoom
        )
    }

    if (newOrderAlert != null) {
        AlertDialog(
            onDismissRequest = onDismissNewOrderAlert,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🛎️ ", fontSize = 24.sp)
                    Text(
                        text = "YENİ SİPARİŞ!",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFDC2626),
                        fontSize = 20.sp
                    )
                }
            },
            text = {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Surface(
                        color = Color(0xFFEFF6FF),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Text(
                            text = newOrderAlert.roomName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = Color(0xFF1E40AF),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Text(
                        text = "Sipariş Detayı:",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = Color(0xFF64748B)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (newOrderAlert.itemsSummary.isNotBlank()) newOrderAlert.itemsSummary else "Detaylar ekranda...",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1E293B)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onDismissNewOrderAlert,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Siparişi Gör & Tamam", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        )
    }
}

@Composable
fun RoleSelectionDialog(
    initialRole: String,
    initialFloor: String,
    initialSlug: String,
    initialName: String,
    availableRooms: List<RoomItem>,
    onDismiss: () -> Unit,
    onApply: (role: String, floor: String, slug: String, name: String) -> Unit
) {
    var selectedRole by remember { mutableStateOf(initialRole) }
    var selectedFloor by remember { mutableStateOf(initialFloor) }
    var selectedSlug by remember { mutableStateOf(initialSlug) }
    var selectedName by remember { mutableStateOf(initialName) }

    val floorOptions = listOf(
        "makam" to "⭐ Makam Katı Mutfağı",
        "kat-3" to "🏢 3. Kat Mutfağı",
        "kat-4" to "🏢 4. Kat Mutfağı",
        "kat-5" to "🏢 5. Kat Mutfağı",
        "kat-6" to "🏢 6. Kat Mutfağı",
        "all" to "🌐 Tüm Mutfaklar (Merkezi)"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Kullanıcı Rolü Seçin",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color(0xFF0F172A)
            )
        },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    Text(
                        text = "Bu cihazda hangi rolde çalışacaksınız?",
                        fontSize = 13.sp,
                        color = Color(0xFF64748B),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // CARD 1: MUTFAK GÖREVLİSİ
                    val isKitchen = selectedRole == OrderNotificationService.ROLE_KITCHEN
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { selectedRole = OrderNotificationService.ROLE_KITCHEN }
                            .border(
                                width = if (isKitchen) 2.dp else 1.dp,
                                color = if (isKitchen) Color(0xFF2563EB) else Color(0xFFCBD5E1),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isKitchen) Color(0xFFEFF6FF) else Color(0xFFF8FAFC)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "👨‍🍳", fontSize = 20.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Mutfak Görevlisi",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = Color(0xFF0F172A)
                                    )
                                    Text(
                                        text = "Sipariş takip ekranı & sesli zil uyarısı",
                                        fontSize = 11.sp,
                                        color = Color(0xFF475569)
                                    )
                                }
                            }

                            if (isKitchen) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Hangi katın mutfağındasınız?",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    color = Color(0xFF1E293B)
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                floorOptions.forEach { (key, label) ->
                                    val isFloorSelected = selectedFloor == key
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isFloorSelected) Color(0xFFDBEAFE) else Color.Transparent)
                                            .clickable { selectedFloor = key }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isFloorSelected) "●" else "○",
                                            color = if (isFloorSelected) Color(0xFF2563EB) else Color(0xFF94A3B8),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = label,
                                            fontSize = 13.sp,
                                            fontWeight = if (isFloorSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isFloorSelected) Color(0xFF1D4ED8) else Color(0xFF334155)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "⚡ Sadece seçtiğiniz katın siparişlerinde telefon çalar. Diğer katlar size düşmez.",
                                    fontSize = 11.sp,
                                    color = Color(0xFF059669),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // CARD 2: ODA / PERSONEL
                    val isRoom = selectedRole == OrderNotificationService.ROLE_ROOM
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable { selectedRole = OrderNotificationService.ROLE_ROOM }
                            .border(
                                width = if (isRoom) 2.dp else 1.dp,
                                color = if (isRoom) Color(0xFF2563EB) else Color(0xFFCBD5E1),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isRoom) Color(0xFFEFF6FF) else Color(0xFFF8FAFC)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "🏢", fontSize = 20.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Oda / Personel (Sipariş Veren)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = Color(0xFF0F172A)
                                    )
                                    Text(
                                        text = "Odanızdan ikram / kahve siparişi verin",
                                        fontSize = 11.sp,
                                        color = Color(0xFF475569)
                                    )
                                }
                            }

                            if (isRoom) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Sabit bir oda seçebilir veya QR kod ile sipariş verebilirsiniz.",
                                    fontSize = 11.sp,
                                    color = Color(0xFF475569)
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                if (selectedSlug.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFFDBEAFE),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "Seçili Oda: ${if (selectedName.isNotBlank()) selectedName else selectedSlug}",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1D4ED8)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // CARD 3: YÖNETİCİ
                    val isAdmin = selectedRole == OrderNotificationService.ROLE_ADMIN
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { selectedRole = OrderNotificationService.ROLE_ADMIN }
                            .border(
                                width = if (isAdmin) 2.dp else 1.dp,
                                color = if (isAdmin) Color(0xFF2563EB) else Color(0xFFCBD5E1),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isAdmin) Color(0xFFEFF6FF) else Color(0xFFF8FAFC)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "⚙️", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Yönetici (Admin Paneli)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = Color(0xFF0F172A)
                                )
                                Text(
                                    text = "Ürün, oda ve sistem yönetimi",
                                    fontSize = 11.sp,
                                    color = Color(0xFF475569)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onApply(selectedRole, selectedFloor, selectedSlug, selectedName)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
            ) {
                Text(text = "Kaydet ve Başla", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Vazgeç", color = Color(0xFF64748B))
            }
        }
    )
}

@Composable
fun RoomPickerDialog(
    availableRooms: List<RoomItem>,
    currentSlug: String,
    onDismiss: () -> Unit,
    onSelect: (RoomItem) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredRooms = remember(searchQuery, availableRooms) {
        if (searchQuery.isBlank()) availableRooms
        else availableRooms.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.slug.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "Oda Seçin",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = "Sipariş vermek istediğiniz odayı belirleyin",
                    fontSize = 12.sp,
                    color = Color(0xFF64748B)
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("🔍 Ara (Örn: Makam, J Büro...)", fontSize = 13.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    singleLine = true
                )

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    if (filteredRooms.isEmpty()) {
                        item {
                            Text(
                                text = "Oda bulunamadı.",
                                modifier = Modifier.padding(16.dp),
                                color = Color(0xFF94A3B8),
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        items(filteredRooms) { room ->
                            val isSelected = room.slug == currentSlug
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFFDBEAFE) else Color(0xFFF8FAFC))
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) Color(0xFF3B82F6) else Color(0xFFE2E8F0),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { onSelect(room) }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = "🚪", fontSize = 16.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = room.name,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            color = Color(0xFF0F172A)
                                        )
                                        Text(
                                            text = OrderNotificationService.getFloorLabel(room.floor),
                                            fontSize = 11.sp,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                }
                                Text(
                                    text = if (isSelected) "✓" else "→",
                                    color = if (isSelected) Color(0xFF2563EB) else Color(0xFF94A3B8),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Kapat", color = Color(0xFF64748B))
            }
        }
    )
}
