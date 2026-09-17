package com.example.transferptp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.transferptp.ui.theme.TransferPTPTheme
import java.text.SimpleDateFormat
import java.util.*

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
                    containerColor = Color(0xFFF8FAFC) 
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
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var serverPin by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }
    var showQRDialog by remember { mutableStateOf(false) }
    val connectedDevices = remember { mutableStateListOf<DeviceConnection>() }

    fun checkStoragePermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    
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
        LoginQRDialog(url = serverUrl!!, pin = serverPin!!, onDismiss = { showQRDialog = false })
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // --- COMPACT HEADER ---
        Spacer(modifier = Modifier.height(20.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF6366F1).copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SwapCalls, 
                    contentDescription = null, 
                    tint = Color(0xFF6366F1),
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = "Transfer PTP", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                Text(text = "Fast and Secure Sharing", fontSize = 11.sp, color = Color(0xFF64748B))
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // --- SERVICE CARD (Start/Stop) ---
        AnimatedContent(targetState = isRunning, label = "ServerStatus") { running ->
            if (running) {
                ActiveServerCard(url = serverUrl ?: "", pin = serverPin ?: "", onStop = { fileServer.stop(); isRunning = false; serverUrl = null; serverPin = null; connectedDevices.clear() })
            } else {
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
                        } catch (e: Exception) { Toast.makeText(context, "Start Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${context.packageName}") }) }
                        else launcher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                })
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- DEVICES HEADER ---
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Connected Devices", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
            if (connectedDevices.isNotEmpty()) Surface(color = Color(0xFFF1F5F9), shape = CircleShape) {
                Text(text = connectedDevices.size.toString(), modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF6366F1))
            }
        }
        
        Spacer(modifier = Modifier.height(10.dp))

        // --- DEVICE LIST ---
        Box(modifier = Modifier.weight(1f)) {
            if (connectedDevices.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(connectedDevices) { device -> DeviceItem(device) { fileServer.disconnectDevice(device.rawInfo) } }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- BOTTOM NAVIGATION ---
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(text = "Scan", icon = Icons.Outlined.QrCodeScanner, containerColor = Color(0xFF6366F1), contentColor = Color.White, modifier = Modifier.weight(1f)) { }
            ActionButton(text = "Login QR", icon = Icons.Default.QrCode, containerColor = Color(0xFF1E293B), contentColor = Color.White, modifier = Modifier.weight(1f)) { 
                if (isRunning) showQRDialog = true else Toast.makeText(context, "Start Server first", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
fun StartServerSection(onStart: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(80.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(text = "Server Offline", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(text = "Enable Transfer", color = Color(0xFF1E293B), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("START", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun ActiveServerCard(url: String, pin: String, onStop: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(80.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF22C55E)))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = url.removePrefix("http://"), color = Color(0xFF6366F1), fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Security, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Authorization PIN: ", fontSize = 10.sp, color = Color(0xFF94A3B8))
                    Text(text = pin, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1E293B), letterSpacing = 2.sp)
                }
            }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFEF2F2), contentColor = Color(0xFFEF4444)),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                border = BorderStroke(1.dp, Color(0xFFFEE2E2))
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("STOP", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun LoginQRDialog(url: String, pin: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val fullUrl = "$url?pin=$pin"
    val qrBitmap = remember(fullUrl) { QRCodeGenerator.generate(fullUrl) }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(0.dp),
            shape = RoundedCornerShape(2.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(12.dp))

                Text(text = "Scan to Connect", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Scan this QR code with any device to quickly access the server", 
                    fontSize = 13.sp, 
                    color = Color(0xFF64748B),
                    textAlign = TextAlign.Center,
                    lineHeight = 14.sp
                )
                
                Spacer(modifier = Modifier.height(15.dp))
                
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Login QR Code",
                    modifier = Modifier.size(240.dp)
                )
                
                Text(text = fullUrl, fontSize = 12.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                
                Spacer(modifier = Modifier.height(5.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "CANCEL",
                            color = Color(0xFF6366F1),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Server URL", fullUrl)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "URL Copied to Clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Text(
                            text = "COPY TO CLIPBOARD",
                            color = Color(0xFF6366F1),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Devices, contentDescription = null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "No devices connected", color = Color(0xFF94A3B8), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun DeviceItem(device: DeviceConnection, onDisconnect: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFF1F5F9))
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF8FAFC)), contentAlignment = Alignment.Center) {
                val icon = when {
                    device.osName.contains("Windows") -> "💻"
                    device.osName.contains("Android") -> "📱"
                    device.osName.contains("iPhone") || device.osName.contains("iPad") -> "🍎"
                    else -> "⚙️"
                }
                Text(text = icon, fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = device.osName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1E293B))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val browserRes = when (device.browserName) {
                        "Chrome" -> R.drawable.ic_chrome
                        "Firefox" -> R.drawable.ic_firefox
                        "Edge" -> R.drawable.ic_edge
                        "Safari" -> R.drawable.ic_safari
                        "Opera" -> R.drawable.ic_opera
                        "UCBrowser" -> R.drawable.ic_ucbrowser
                        else -> R.drawable.ic_chrome
                    }
                    Icon(painter = painterResource(id = browserRes), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "${device.browserName} v${device.browserVersion}", fontSize = 12.sp, color = Color(0xFF64748B), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(text = "${device.ip} • ${device.pairedAt}", fontSize = 10.sp, color = Color(0xFF94A3B8))
            }
            IconButton(onClick = onDisconnect, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.LinkOff, contentDescription = "Disconnect", tint = Color(0xFFEF4444).copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun ActionButton(text: String, icon: ImageVector, containerColor: Color, contentColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, 
        modifier = modifier.height(52.dp), 
        shape = RoundedCornerShape(14.dp), 
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

data class DeviceConnection(val osName: String, val browserName: String, val browserVersion: String, val ip: String, val pairedAt: String, val rawInfo: String)
