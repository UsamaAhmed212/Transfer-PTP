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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.transferptp.ui.theme.TransferPTPTheme

class MainActivity : ComponentActivity() {
    private var fileServer: FileServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate")
        fileServer = FileServer(this)
        enableEdgeToEdge()
        setContent {
            TransferPTPTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TransferScreen(
                        fileServer = fileServer!!,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d("MainActivity", "onDestroy")
        super.onDestroy()
        // fileServer?.stop() // Temporarily disable automatic stop on destroy
    }
}

@Composable
fun TransferScreen(fileServer: FileServer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }

    fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Update permission status periodically or when screen opens
    LaunchedEffect(Unit) {
        hasPermission = checkStoragePermission()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (isGranted) {
            Toast.makeText(context, "Permission Granted", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Transfer PTP",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Show permission warning if not granted
        if (!hasPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) {
                Text(
                    text = "⚠️ Storage Access Required\nPlease allow 'All Files Access' to transfer files.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isRunning) {
            Text(text = "Server is running at:", fontSize = 18.sp)
            Text(
                text = serverUrl ?: "",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                Log.d("MainActivity", "Stop Server clicked")
                fileServer.stop()
                isRunning = false
                serverUrl = null
            }) {
                Text("Stop Server")
            }
        } else {
            Button(
                onClick = {
                    Log.d("MainActivity", "Start Server clicked")
                    if (checkStoragePermission()) {
                        fileServer.start { url ->
                            Log.d("MainActivity", "Server started callback with URL: $url")
                            serverUrl = url
                            isRunning = true
                        }
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                            Toast.makeText(context, "Grant 'All Files Access' then come back", Toast.LENGTH_LONG).show()
                        } else {
                            launcher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    }
                },
                modifier = Modifier.height(56.dp).fillMaxWidth(0.7f)
            ) {
                Text(if (hasPermission) "Start Server" else "Grant Permission & Start")
            }
        }
    }
}
