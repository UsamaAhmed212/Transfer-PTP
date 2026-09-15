package com.example.transferptp

import android.Manifest
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                Scaffold(modifier = Modifier.fillMaxSize(), containerColor = Color(0xFFF7F9FC)) { innerPadding ->
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

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.SwapCalls, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = "Transfer PTP", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1C1E))
                Text(text = "Fast and Secure File Transfer", fontSize = 13.sp, color = Color(0xFF74777F))
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        if (!hasPermission) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFDAD6)), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFBA1A1A)); Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "Storage access required to transfer files.", fontSize = 14.sp, color = Color(0xFF410002))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
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
        Spacer(modifier = Modifier.height(32.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Connected Devices", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1C1E))
            if (connectedDevices.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = CircleShape) {
                Text(text = connectedDevices.size.toString(), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (connectedDevices.isEmpty()) EmptyState()
            else LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(connectedDevices) { device -> DeviceItem(device) { fileServer.disconnectDevice(device.rawInfo) } }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton(text = "Scan", icon = Icons.Outlined.QrCodeScanner, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.weight(1f)) { }
            ActionButton(text = "Login QR", icon = Icons.Default.QrCode, containerColor = Color(0xFF2B2D31), contentColor = Color.White, modifier = Modifier.weight(1f)) { }
        }
    }
}

@Composable
fun StartServerSection(onStart: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(28.dp)).background(Brush.verticalGradient(listOf(Color(0xFF6750A4), Color(0xFF4F378B)))).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Start transferring files now", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp); Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp), modifier = Modifier.height(48.dp).fillMaxWidth(0.7f)) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF6750A4)); Spacer(modifier = Modifier.width(8.dp))
                Text("START SERVER", fontWeight = FontWeight.ExtraBold, color = Color(0xFF6750A4))
            }
        }
    }
}

@Composable
fun ActiveServerCard(url: String, pin: String, onStop: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), border = BorderStroke(1.dp, Color(0xFFE0E4EB))) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF4CAF50))); Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Server is Live", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
            }
            Spacer(modifier = Modifier.height(16.dp)); Text(text = url, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, textAlign = TextAlign.Center); Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFF3F5F9), RoundedCornerShape(16.dp)).padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF74777F), modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Authorization PIN: ", fontSize = 13.sp, color = Color(0xFF74777F)); Text(text = pin, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1A1C1E), letterSpacing = 2.sp)
            }
            Spacer(modifier = Modifier.height(12.dp)); TextButton(onClick = onStop) { Text("Stop and Disconnect", color = Color(0xFFBA1A1A), fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun EmptyState() {
    Box(modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFFF3F5F9)).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Devices, contentDescription = null, tint = Color(0xFFAEB2BB), modifier = Modifier.size(32.dp)); Spacer(modifier = Modifier.height(8.dp))
            Text(text = "No devices connected yet", color = Color(0xFF74777F), fontSize = 14.sp)
        }
    }
}

@Composable
fun DeviceItem(device: DeviceConnection, onDisconnect: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFEEF1F6))) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFE8DEF8)), contentAlignment = Alignment.Center) {
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
                Text(text = device.osName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1A1C1E))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val browserRes = when (device.browserName) {
                        "Chrome" -> R.drawable.ic_chrome
                        "Firefox" -> R.drawable.ic_firefox
                        "Edge" -> R.drawable.ic_edge
                        "Safari" -> R.drawable.ic_safari
                        "Opera" -> R.drawable.ic_opera
                        else -> R.drawable.ic_chrome
                    }
                    Icon(
                        painter = painterResource(id = browserRes),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "${device.browserName}/${device.browserVersion}", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Color(0xFF49454F), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(text = "${device.ip} • ${device.pairedAt}", fontSize = 11.sp, color = Color(0xFF74777F))
            }
            Icon(imageVector = Icons.Default.LinkOff, contentDescription = "Disconnect", tint = Color(0xFFE57373), modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onDisconnect() }.padding(4.dp))
        }
    }
}

@Composable
fun ActionButton(text: String, icon: ImageVector, containerColor: Color, contentColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(64.dp), shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor), elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)); Spacer(modifier = Modifier.width(10.dp)); Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

data class DeviceConnection(val osName: String, val browserName: String, val browserVersion: String, val ip: String, val pairedAt: String, val rawInfo: String)
