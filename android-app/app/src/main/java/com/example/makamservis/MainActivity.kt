package com.example.makamservis

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var webViewRef: WebView? = null
    private var isConnectedState = mutableStateOf(false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                OrderNotificationService.BROADCAST_NEW_ORDER -> {
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
        startOrderService()

        val filter = IntentFilter().apply {
            addAction(OrderNotificationService.BROADCAST_NEW_ORDER)
            addAction(OrderNotificationService.BROADCAST_STATUS_CHANGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        val prefs = getSharedPreferences(OrderNotificationService.PREFS_NAME, Context.MODE_PRIVATE)
        val savedFloor = prefs.getString(OrderNotificationService.PREF_FLOOR, "makam") ?: "makam"

        setContent {
            MaterialTheme {
                KitchenMainScreen(
                    initialFloor = savedFloor,
                    isConnected = isConnectedState.value,
                    onFloorChanged = { newFloor ->
                        changeFloor(newFloor)
                    },
                    onTestAlert = {
                        testAlert()
                    },
                    onRefresh = {
                        webViewRef?.reload()
                    },
                    onWebViewCreated = { webView ->
                        webViewRef = webView
                    }
                )
            }
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

    private fun startOrderService() {
        val intent = Intent(this, OrderNotificationService::class.java).apply {
            action = OrderNotificationService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun changeFloor(floor: String) {
        val intent = Intent(this, OrderNotificationService::class.java).apply {
            action = OrderNotificationService.ACTION_CHANGE_FLOOR
            putExtra(OrderNotificationService.EXTRA_FLOOR, floor)
        }
        startService(intent)
        val url = "http://${OrderNotificationService.SERVER_HOST}/mutfak/$floor"
        webViewRef?.loadUrl(url)
    }

    private fun testAlert() {
        val intent = Intent(this, OrderNotificationService::class.java).apply {
            action = OrderNotificationService.ACTION_TEST_NOTIFICATION
        }
        startService(intent)
        Toast.makeText(this, "🔔 Test uyarısı çalındı!", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KitchenMainScreen(
    initialFloor: String,
    isConnected: Boolean,
    onFloorChanged: (String) -> Unit,
    onTestAlert: () -> Unit,
    onRefresh: () -> Unit,
    onWebViewCreated: (WebView) -> Unit
) {
    var selectedFloor by remember { mutableStateOf(initialFloor) }
    var menuExpanded by remember { mutableStateOf(false) }

    val floors = listOf(
        "makam" to "⭐ Makam Katı",
        "kat-3" to "🏢 3. Kat",
        "kat-4" to "🏢 4. Kat",
        "kat-5" to "🏢 5. Kat",
        "kat-6" to "🏢 6. Kat",
        "all" to "🌐 Tüm Katlar"
    )

    val currentLabel = floors.firstOrNull { it.first == selectedFloor }?.second ?: "⭐ Makam Katı"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Makam Servis",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // Status Pill
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isConnected) Color(0xFF10B981) else Color(0xFFF59E0B)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isConnected) "● Canlı" else "● Bağlanıyor",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                        // Floor dropdown selector
                        Box {
                            Row(
                                modifier = Modifier
                                    .clickable { menuExpanded = true }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$currentLabel ▼",
                                    fontSize = 13.sp,
                                    color = Color(0xFFFDE047),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                floors.forEach { (floorKey, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label, fontWeight = if (floorKey == selectedFloor) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = {
                                            selectedFloor = floorKey
                                            menuExpanded = false
                                            onFloorChanged(floorKey)
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onTestAlert) {
                        Text(text = "🔔", fontSize = 18.sp)
                    }
                    IconButton(onClick = onRefresh) {
                        Text(text = "🔄", fontSize = 18.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E1B4B)
                )
            )
        }
    ) { innerPadding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
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
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mediaPlaybackRequiresUserGesture = false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            url?.let { view?.loadUrl(it) }
                            return true
                        }
                    }
                    webChromeClient = WebChromeClient()

                    val targetUrl = "http://${OrderNotificationService.SERVER_HOST}/mutfak/$selectedFloor"
                    loadUrl(targetUrl)
                    onWebViewCreated(this)
                }
            },
            update = { webView ->
                // WebView updates on floor change
            }
        )
    }
}
