package com.example.transferptp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.example.transferptp.ui.theme.TransferPTPTheme
import java.text.SimpleDateFormat
import java.util.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URL

class MainActivity : ComponentActivity() {
    private var fileServer: FileServer? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileServer = FileServer(this)
        enableEdgeToEdge()
        setContent {
            TransferPTPTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(), 
                    containerColor = Color(0xFFF7F9FC) 
                ) { innerPadding ->
                    TransferScreen(fileServer = fileServer!!, modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun TransferScreen(fileServer: FileServer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var serverPin by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }
    var showQRDialog by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var scannerSuccess by remember { mutableStateOf(false) }
    var waitingForDevice by remember { mutableStateOf(false) }
    var initialDeviceCount by remember { mutableIntStateOf(0) }
    val connectedDevices = remember { mutableStateListOf<DeviceConnection>() }

    // --- SHARED TOAST STATE ---
    var customToastMessage by remember { mutableStateOf("") }
    var lastToastMessage by remember { mutableStateOf("") }
    var lastToastTime by remember { mutableLongStateOf(0L) }
    var showCustomToast by remember { mutableStateOf(false) }

    fun showToast(message: String) {
        val now = System.currentTimeMillis()
        if (message == lastToastMessage && now - lastToastTime < 2500) return
        showCustomToast = false
        scope.launch {
            delay(50)
            customToastMessage = message
            lastToastMessage = message
            lastToastTime = now
            showCustomToast = true
            delay(2500)
            if (customToastMessage == message) showCustomToast = false
        }
    }

    LaunchedEffect(connectedDevices.size) {
        if (waitingForDevice && connectedDevices.size > initialDeviceCount) {
            waitingForDevice = false
            delay(500)
            showScanner = false
            scannerSuccess = false
        }
    }

    fun checkStoragePermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    
    val connectivityManager = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    DisposableEffect(isRunning) {
        if (!isRunning) return@DisposableEffect onDispose {}
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Handler(Looper.getMainLooper()).postDelayed({
                    val newIp = fileServer.getLocalIpAddress()
                    if (newIp != null) { serverUrl = "http://$newIp:8080" }
                }, 1200)
            }
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        onDispose { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }

    LaunchedEffect(Unit) { 
        hasPermission = checkStoragePermission()
        if (FileServer.isServerRunning()) {
            isRunning = true
            serverUrl = FileServer.getSavedUrl()
            serverPin = FileServer.getSavedPin()
            fileServer.start(onStarted = { _, _ -> }, onDevicesUpdated = { devices ->
                connectedDevices.clear()
                devices.forEach { rawInfo ->
                    val parts = rawInfo.split("|")
                    if (parts.size == 4) connectedDevices.add(DeviceConnection(osName = parts[0], browserName = parts[1], browserVersion = parts[2], ip = parts[3], pairedAt = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date()), rawInfo = rawInfo))
                }
            })
        }
    }
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }

    if (showQRDialog && serverUrl != null && serverPin != null) {
        LoginQRDialog(
            url = serverUrl!!, 
            pin = serverPin!!, 
            showCustomToast = showCustomToast,
            customToastMessage = customToastMessage,
            onShowToast = { msg -> showToast(msg) }, 
            onDismiss = { 
                showQRDialog = false
                showCustomToast = false // Stop toast immediately when dialog closes to prevent double toast
            }
        )
    }

    if (showScanner) {
        QRScannerOverlay(
            isSuccess = scannerSuccess,
            showCustomToast = showCustomToast,
            customToastMessage = customToastMessage,
            onScan = { authId, onVerified ->
                if (scannerSuccess) return@QRScannerOverlay
                val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                if (!uuidRegex.matches(authId)) {
                    showToast("Invalid QR Code")
                    onVerified(false)
                    return@QRScannerOverlay
                }
                Thread {
                    try {
                        val port = serverUrl?.substringAfterLast(":") ?: "8080"
                        val localUrl = "http://127.0.0.1:$port/approve-qr?id=$authId"
                        val response = URL(localUrl).readText()
                        Handler(Looper.getMainLooper()).post {
                            if (response == "OK") {
                                scannerSuccess = true
                                initialDeviceCount = connectedDevices.size
                                waitingForDevice = true
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (waitingForDevice) { showScanner = false; scannerSuccess = false; waitingForDevice = false }
                                }, 400)
                            } else { showToast("Invalid QR Code"); onVerified(false) }
                        }
                    } catch(e: Exception) {
                        Handler(Looper.getMainLooper()).post { onVerified(false); showToast("Connection Error") }
                    }
                }.start()
            },
            onDismiss = { 
                showScanner = false
                scannerSuccess = false
                waitingForDevice = false
                showCustomToast = false // Stop toast immediately when scanner closes
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(imageVector = Icons.Default.SwapCalls, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp)) }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(text = "Transfer PTP", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1A1C1E))
                    Text(text = "Fast and Secure Sharing", fontSize = 12.sp, color = Color(0xFF74777F))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            AnimatedContent(targetState = isRunning, label = "ServerStatus") { running ->
                if (running) { ActiveServerCard(url = serverUrl ?: "", pin = serverPin ?: "", onStop = { fileServer.stop(); isRunning = false; serverUrl = null; serverPin = null; connectedDevices.clear() }) }
                else {
                    StartServerSection(onStart = {
                        if (checkStoragePermission()) {
                            try {
                                fileServer.start(onStarted = { url, pin -> serverUrl = url; serverPin = pin; isRunning = true }, onDevicesUpdated = { devices ->
                                    connectedDevices.clear()
                                    devices.forEach { rawInfo ->
                                        val parts = rawInfo.split("|")
                                        if (parts.size == 4) connectedDevices.add(DeviceConnection(osName = parts[0], browserName = parts[1], browserVersion = parts[2], ip = parts[3], pairedAt = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date()), rawInfo = rawInfo))
                                    }
                                })
                            } catch (e: Exception) { showToast(if (e.message == "WIFI not connected") "WIFI not connected" else "Start Error: ${e.localizedMessage}") }
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${context.packageName}") }) }
                            else launcher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    })
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Connected Devices", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                if (connectedDevices.isNotEmpty()) Surface(color = Color(0xFFF1F5F9), shape = CircleShape) { Text(text = connectedDevices.size.toString(), modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF6366F1)) }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (connectedDevices.isEmpty()) EmptyState()
                else LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(connectedDevices) { device -> DeviceItem(device) { fileServer.disconnectDevice(device.rawInfo) } } }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(text = "Scan", icon = Icons.Outlined.QrCodeScanner, containerColor = Color(0xFF6366F1), contentColor = Color.White, modifier = Modifier.weight(1f)) { if (isRunning) showScanner = true else showToast("Start Server first") }
                ActionButton(text = "Login QR", icon = Icons.Default.QrCode, containerColor = Color(0xFF1E293B), contentColor = Color.White, modifier = Modifier.weight(1f)) { if (isRunning) showQRDialog = true else showToast("Start Server first") }
            }
        }

        // --- RENDER CUSTOM TOAST ON MAIN SCREEN (Only if no dialog/scanner is open) ---
        CustomHUDToast(visible = showCustomToast && !showScanner && !showQRDialog, message = customToastMessage)
    }
}

@Composable
fun CustomHUDToast(visible: Boolean, message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn() + scaleIn(initialScale = 0.9f),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.padding(bottom = 100.dp)
        ) {
            Surface(
                color = Color(0xFF2B2D31).copy(alpha = 0.9f),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 8.dp,
                modifier = Modifier.padding(horizontal = 12.dp) // More width for long text
            ) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = message, 
                        color = Color.White, 
                        fontSize = 13.sp, 
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false, // Force it to stay on one line
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun StartServerSection(onStart: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().height(80.dp), shape = RoundedCornerShape(20.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFE2E8F0))) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(text = "Server Offline", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(text = "Enable Transfer", color = Color(0xFF1E293B), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp)); Text("START", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun ActiveServerCard(url: String, pin: String, onStop: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().height(80.dp), shape = RoundedCornerShape(20.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.2f))) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF22C55E)))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = url.removePrefix("http://"), color = Color(0xFF6366F1), fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Security, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp)); Text(text = "Authorization PIN: ", fontSize = 10.sp, color = Color(0xFF94A3B8))
                    Text(text = pin, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1E293B), letterSpacing = 2.sp)
                }
            }
            Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFEF2F2), contentColor = Color(0xFFEF4444)), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), border = BorderStroke(1.dp, Color(0xFFFEE2E2))) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp)); Text("STOP", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun LoginQRDialog(
    url: String, 
    pin: String, 
    showCustomToast: Boolean,
    customToastMessage: String,
    onShowToast: (String) -> Unit, 
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val fullUrl = "$url?pin=$pin"
    val qrBitmap = remember(fullUrl) { QRCodeGenerator.generate(fullUrl) }
    Dialog(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(modifier = Modifier.fillMaxWidth().padding(0.dp), shape = RoundedCornerShape(2.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(12.dp)); Text(text = "Scan to Connect", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                    Spacer(modifier = Modifier.height(10.dp)); Text(text = "Scan this QR code with any device to quickly access the server", fontSize = 13.sp, color = Color(0xFF64748B), textAlign = TextAlign.Center, lineHeight = 14.sp)
                    Spacer(modifier = Modifier.height(15.dp)); Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "Login QR Code", modifier = Modifier.size(240.dp))
                    Text(text = fullUrl, fontSize = 12.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(text = "CANCEL", color = Color(0xFF6366F1), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        Spacer(modifier = Modifier.width(8.dp));
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Server URL", fullUrl))
                            onShowToast("IP Address Copied to Clipboard")
                        }) { Text(text = "COPY TO CLIPBOARD", color = Color(0xFF6366F1), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    }
                }
            }
            CustomHUDToast(visible = showCustomToast, message = customToastMessage)
        }
    }
}

@Composable
fun EmptyState() {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Devices, contentDescription = null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(12.dp)); Text(text = "No devices connected", color = Color(0xFF94A3B8), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun DeviceItem(device: DeviceConnection, onDisconnect: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFF1F5F9))) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF8FAFC)), contentAlignment = Alignment.Center) {
                val icon = when { device.osName.contains("Windows") -> "💻"; device.osName.contains("Android") -> "📱"; device.osName.contains("iPhone") || device.osName.contains("iPad") -> "🍎"; else -> "⚙️" }
                Text(text = icon, fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = device.osName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1E293B))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val browserRes = when (device.browserName) { "Chrome" -> R.drawable.ic_chrome; "Firefox" -> R.drawable.ic_firefox; "Edge" -> R.drawable.ic_edge; "Safari" -> R.drawable.ic_safari; "Opera" -> R.drawable.ic_opera; "UCBrowser" -> R.drawable.ic_ucbrowser; else -> R.drawable.ic_chrome }
                    Icon(painter = painterResource(id = browserRes), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp)); Text(text = "${device.browserName} v${device.browserVersion}", fontSize = 12.sp, color = Color(0xFF64748B), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(text = "${device.ip} • ${device.pairedAt}", fontSize = 10.sp, color = Color(0xFF94A3B8))
            }
            IconButton(onClick = onDisconnect, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.LinkOff, contentDescription = "Disconnect", tint = Color(0xFFEF4444).copy(alpha = 0.7f), modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
fun QRScannerOverlay(
    isSuccess: Boolean,
    showCustomToast: Boolean,
    customToastMessage: String,
    onScan: (String, (Boolean) -> Unit) -> Unit, 
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var isVerifying by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCameraPermission = it }

    LaunchedEffect(Unit) { if (!hasCameraPermission) launcher.launch(Manifest.permission.CAMERA) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (hasCameraPermission) {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                                val barcodeScanner = BarcodeScanning.getClient()
                                val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                                    if (!isSuccess && !isVerifying) {
                                        @OptIn(ExperimentalGetImage::class) val mediaImage = imageProxy.image
                                        if (mediaImage != null) {
                                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                            barcodeScanner.process(image)
                                                .addOnSuccessListener { barcodes ->
                                                    for (barcode in barcodes) {
                                                        barcode.rawValue?.let { 
                                                            isVerifying = true
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            onScan(it) { verified -> if (!verified) isVerifying = false }
                                                        }
                                                    }
                                                }
                                                .addOnCompleteListener { imageProxy.close() }
                                        } else imageProxy.close()
                                    } else imageProxy.close()
                                }
                                try { cameraProvider.unbindAll(); cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis) } catch (e: Exception) { }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val trans = rememberInfiniteTransition(label = "laser")
                        val laserOffset by trans.animateFloat(0f, 1f, infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Reverse), label = "laser")
                        Canvas(modifier = Modifier.size(280.dp)) {
                            val sw = 2.dp.toPx(); val cs = 50.dp.toPx(); val col = Color(0xFF6366F1)
                            drawLine(col, Offset(0f, 0f), Offset(cs, 0f), sw); drawLine(col, Offset(0f, 0f), Offset(0f, cs), sw)
                            drawLine(col, Offset(size.width, 0f), Offset(size.width - cs, 0f), sw); drawLine(col, Offset(size.width, 0f), Offset(size.width, cs), sw)
                            drawLine(col, Offset(0f, size.height), Offset(cs, size.height), sw); drawLine(col, Offset(0f, size.height), Offset(0f, size.height - cs), sw)
                            drawLine(col, Offset(size.width, size.height), Offset(size.width - cs, size.height), sw); drawLine(col, Offset(size.width, size.height), Offset(size.width, size.height - cs), sw)
                            val y = size.height * laserOffset
                            if (!isSuccess) drawLine(Brush.horizontalGradient(listOf(col.copy(0f), col, col.copy(0f))), Offset(10f, y), Offset(size.width - 10f, y), 2.dp.toPx())
                        }
                    }
                }

                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(24.dp).background(Color.Black.copy(0.5f), CircleShape)) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }

                AnimatedVisibility(visible = isSuccess, enter = fadeIn(tween(400)) + scaleIn(initialScale = 0.8f, animationSpec = tween(400, easing = FastOutSlowInEasing)), exit = fadeOut(tween(300))) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(contentAlignment = Alignment.Center) {
                                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                val ringAlpha by infiniteTransition.animateFloat(initialValue = 0.8f, targetValue = 0f, animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "alpha")
                                val ringScale by infiniteTransition.animateFloat(initialValue = 1f, targetValue = 1.6f, animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "scale")
                                Box(modifier = Modifier.size(80.dp).scale(ringScale).background(Color.White.copy(alpha = ringAlpha), CircleShape))
                                Surface(shape = CircleShape, color = Color.White, modifier = Modifier.size(85.dp), shadowElevation = 10.dp) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, tint = Color(0xFF6366F1), modifier = Modifier.size(45.dp)) } }
                            }
                            Spacer(modifier = Modifier.height(32.dp))
                            Text(text = "AUTHORIZED", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "Secure connection established", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // --- RENDER CUSTOM TOAST ON SCANNER OVERLAY ---
                CustomHUDToast(visible = showCustomToast, message = customToastMessage)
                
                if (!hasCameraPermission && !isSuccess) {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(modifier = Modifier.size(80.dp), shape = CircleShape, color = Color(0xFF6366F1).copy(alpha = 0.1f)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CameraAlt, null, tint = Color(0xFF6366F1), modifier = Modifier.size(32.dp)) } }
                            Spacer(modifier = Modifier.height(24.dp)); Text("Camera Permission Required", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp)); Text("To scan QR codes and connect devices, we need access to your camera.", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(32.dp)); Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, colors = ButtonDefaults.buttonColors(Color(0xFF6366F1)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("GRANT PERMISSION", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActionButton(text: String, icon: ImageVector, containerColor: Color, contentColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(52.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor), elevation = ButtonDefaults.buttonElevation(0.dp)) {
        Icon(icon, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(8.dp)); Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

data class DeviceConnection(val osName: String, val browserName: String, val browserVersion: String, val ip: String, val pairedAt: String, val rawInfo: String)
