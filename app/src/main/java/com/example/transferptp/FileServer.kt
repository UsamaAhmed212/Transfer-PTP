package com.example.transferptp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import kotlinx.html.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class UserSession(val token: String)

class FileServer(private val context: Context) {
    
    private var currentPin: String = ""
    
    companion object {
        private var server: EmbeddedServer<*, *>? = null
        private val activeSessions = ConcurrentHashMap<String, String>() 
        private val pendingAuthRequests = ConcurrentHashMap<String, String>() // ID -> UserAgent
        
        private var onDevicesUpdated: ((List<String>) -> Unit)? = null
        private var lastUrl: String? = null
        private var lastPin: String? = null
        private var serverSecret: String = ""

        fun isServerRunning(): Boolean = server != null
        fun getSavedUrl(): String? = lastUrl
        fun getSavedPin(): String? = lastPin

        private fun initStorage(context: Context) {
            val prefs = context.getSharedPreferences("server_prefs", Context.MODE_PRIVATE)
            serverSecret = prefs.getString("secret", "") ?: ""
            if (serverSecret.isEmpty()) {
                serverSecret = generateNonce()
                prefs.edit().putString("secret", serverSecret).apply()
            }
            val savedSessions = prefs.getString("active_sessions", "{}") ?: "{}"
            try {
                val map = Json.decodeFromString<Map<String, String>>(savedSessions)
                activeSessions.clear()
                activeSessions.putAll(map)
            } catch (e: Exception) { }
        }

        private fun saveSessions(context: Context) {
            val prefs = context.getSharedPreferences("server_prefs", Context.MODE_PRIVATE)
            val json = Json.encodeToString(activeSessions.toMap())
            prefs.edit().putString("active_sessions", json).apply()
        }

        fun stopServer() {
            try { server?.stop(200, 500) } catch (e: Exception) { } finally { server = null }
        }
        
        fun disconnectDevice(context: Context, displayInfo: String) {
            val tokensToRemove = activeSessions.filterValues { it == displayInfo }.keys
            tokensToRemove.forEach { activeSessions.remove(it) }
            saveSessions(context)
            notifyDevices()
        }

        fun approveAuthRequest(authId: String): Boolean {
            if (pendingAuthRequests.containsKey(authId)) {
                pendingAuthRequests[authId] = "APPROVED" 
                return true
            }
            return false
        }

        fun notifyDevices() {
            val deviceList = activeSessions.values.distinct().toList()
            Handler(Looper.getMainLooper()).post {
                onDevicesUpdated?.invoke(deviceList)
            }
        }
    }

    fun approveAuthRequest(authId: String) = Companion.approveAuthRequest(authId)

    fun disconnectDevice(displayInfo: String) {
        disconnectDevice(context, displayInfo)
    }

    fun stop() {
        stopServer()
    }

    fun start(
        port: Int = 8080, 
        onStarted: (String, String) -> Unit,
        onDevicesUpdated: (List<String>) -> Unit
    ) {
        initStorage(context)
        Companion.onDevicesUpdated = onDevicesUpdated
        
        if (server != null) {
            onStarted(lastUrl ?: "", lastPin ?: "")
            notifyDevices()
            return
        }

        currentPin = (1000..9999).random().toString()
        lastPin = currentPin

        val ip = getLocalIpAddress()
        if (ip == null) {
            throw Exception("WIFI not connected")
        }

        try {
            val newServer = embeddedServer(CIO, port = port) {
                install(Sessions) {
                    cookie<UserSession>("USER_SESSION") {
                        cookie.path = "/"
                        transform(SessionTransportTransformerMessageAuthentication(serverSecret.toByteArray()))
                        cookie.maxAgeInSeconds = 31536000 
                    }
                }
                install(PartialContent)
                
                routing {
                    get("/") {
                        var session = call.sessions.get<UserSession>()
                        val queryPin = call.request.queryParameters["pin"]

                        // 1. Auto-login via URL PIN
                        if (queryPin != null && queryPin == currentPin) {
                            val token = UUID.randomUUID().toString()
                            val clientIP = call.request.local.remoteHost
                            val userAgent = call.request.headers["User-Agent"] ?: "Unknown"
                            activeSessions[token] = parseDeviceInfo(userAgent, clientIP)
                            saveSessions(context)
                            call.sessions.set(UserSession(token))
                            notifyDevices()
                            
                            // Success UI with 100ms delay and animation
                            val successHtml = """
                                <!DOCTYPE html>
                                <html lang="en">
                                <head>
                                   <meta charset="UTF-8">
                                   <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                   <style>
                                       * { margin: 0; padding: 0; box-sizing: border-box; }
                                       :root { --bg: #e7ebf0; --light: #ffffff; --dark: #b8c0ca; --text: #39424e; --accent: #4F46E5; --success: #16a085; --danger: #e74c3c; }
                                       body { min-height: 100vh; display: flex; justify-content: center; align-items: center; font-family: Arial, sans-serif; background: var(--bg); overflow: hidden; }
                                       .otp-card { position: relative; z-index: 2; background: var(--bg); border-radius: 25px; padding: 42px 35px; text-align: center; width: 420px; max-width: calc(100% - 30px); box-shadow: 18px 18px 35px rgba(163, 174, 187, .65), -18px -18px 35px rgba(255, 255, 255, .95); }
                                       .success-screen { display: block; animation: successAppear .5s ease forwards; }
                                       @keyframes successAppear { from { opacity: 0; transform: scale(.8); } to { opacity: 1; transform: scale(1); } }
                                       .success-icon { width: 100px; height: 100px; margin: 0 auto 25px; border-radius: 50%; display: flex; justify-content: center; align-items: center; font-size: 50px; color: var(--success); background: var(--bg); box-shadow: inset 7px 7px 14px var(--dark), inset -7px -7px 14px var(--light), 8px 8px 18px rgba(163, 174, 187, .4), -8px -8px 18px rgba(255, 255, 255, .8); animation: successPop .7s cubic-bezier(.17, .67, .35, 1.4) both; }
                                       @keyframes successPop { 0% { transform: scale(0) rotate(-90deg); opacity: 0; } 70% { transform: scale(1.15) rotate(10deg); opacity: 1; } 100% { transform: scale(1) rotate(0); opacity: 1; } }
                                       h2 { color: var(--success); margin-bottom: 10px; font-weight: 800; }
                                       p { color: #77818d; font-size: 14px; line-height: 1.6; }
                                   </style>
                                </head>
                                <body>
                                    <div class="otp-card">
                                        <div class="success-screen">
                                            <div class="success-icon">✓</div>
                                            <h2>Access Granted</h2>
                                            <p>Connection established. Loading your files...</p>
                                        </div>
                                    </div>
                                    <script>setTimeout(() => window.location.href = '/', 100);</script>
                                </body>
                                </html>
                            """.trimIndent()
                            return@get call.respondText(successHtml, ContentType.Text.Html)
                        }

                        if (session != null && activeSessions.containsKey(session.token)) {
                            notifyDevices()
                            val root = Environment.getExternalStorageDirectory()
                            val path = call.parameters["path"] ?: ""
                            val currentDir = if (path.isEmpty()) File(root.absolutePath) else File(root, path)

                            call.respondHtml {
                                head {
                                    title { +"Transfer PTP" }
                                    style {
                                        +"""
                                            body { font-family: 'Segoe UI', sans-serif; padding: 20px; background-color: #f8f9fa; }
                                            .container { max-width: 900px; margin: 0 auto; background: white; padding: 25px; border-radius: 12px; box-shadow: 0 4px 10px rgba(0,0,0,0.1); }
                                            .breadcrumb { display: flex; align-items: center; background: #f0f2f5; padding: 5px 10px; border-radius: 8px; margin: 5px 0; font-size: 0.95rem; overflow-x: auto; white-space: nowrap; border: 1px solid #ddd; }
                                            .breadcrumb a { text-decoration: none; color: #007bff; font-weight: 500; padding: 2px 6px; border-radius: 4px; }
                                            .breadcrumb a:hover { background: #e7f1ff; text-decoration: underline; }
                                            .breadcrumb .separator { color: #888; margin: 0 8px; font-weight: bold; }
                                            .breadcrumb .current { color: #333; font-weight: bold; padding: 2px 6px; }
                                            ul { list-style: none; padding: 0; margin: 0; }
                                            li { display: flex; align-items: center; padding: 5px; border-bottom: 1px solid #eee; }
                                            .thumb-container { width: 60px; height: 60px; margin-right: 15px; display: flex; align-items: center; justify-content: center; background: #f0f0f0; border-radius: 8px; overflow: hidden; flex-shrink: 0; }
                                            .thumb-container img { width: 100%; height: 100%; object-fit: cover; }
                                            .icon { font-size: 1.5rem; }
                                            .file-info { flex-grow: 1; min-width: 0; }
                                            .file-name { font-weight: 500; color: #007bff; text-decoration: none; word-break: break-all; cursor: pointer; }
                                            .file-meta { font-size: 0.8rem; color: #6c757d; margin-top: 4px; }
                                            .actions { display: flex; gap: 8px; }
                                            .btn { padding: 5px 10px; border-radius: 4px; text-decoration: none; font-size: 0.8rem; cursor: pointer; border: none; }
                                            .btn-preview { background: #007bff; color: white; }
                                            .btn-download { background: #28a745; color: white; }
                                            #preview-overlay { display: none; position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.95); z-index: 1000; justify-content: center; align-items: center; flex-direction: column; }
                                            #preview-content { width: 90%; height: 80%; display: flex; flex-direction: column; align-items: center; gap: 20px; overflow: hidden; }
                                            .close-btn { position: absolute; top: 20px; right: 30px; color: white; font-size: 40px; cursor: pointer; }
                                            audio, video { width: 100%; max-width: 600px; outline: none; }
                                            .music-art { width: 300px; height: 300px; border-radius: 15px; box-shadow: 0 10px 30px rgba(0,0,0,0.5); object-fit: cover; }
                                            .text-preview { background: #1e1e1e; color: #d4d4d4; padding: 20px; width: 100%; height: 100%; overflow: auto; border-radius: 8px; font-family: monospace; white-space: pre-wrap; font-size: 14px; text-align: left; }
                                            iframe { border: none; width: 100%; height: 100%; border-radius: 8px; background: white; }
                                        """.trimIndent()
                                    }
                                    script {
                                        +"""
                                            async function showPreview(url, type, name, thumbUrl) {
                                                const overlay = document.getElementById('preview-overlay');
                                                const content = document.getElementById('preview-content');
                                                overlay.style.display = 'flex';
                                                content.innerHTML = '';
                                                if (type === 'image') {
                                                    const img = document.createElement('img'); img.src = url; img.style.maxWidth = '100%'; img.style.maxHeight = '100%'; content.appendChild(img);
                                                } else if (type === 'audio') {
                                                    const art = document.createElement('img'); art.src = thumbUrl; art.className = 'music-art'; art.onerror = function() { this.src = 'https://cdn-icons-png.flaticon.com/512/3844/3844724.png'; }; content.appendChild(art);
                                                    const audio = document.createElement('audio'); audio.src = url; audio.controls = true; audio.autoplay = true; audio.preload = 'metadata'; content.appendChild(audio);
                                                } else if (type === 'video') {
                                                    const video = document.createElement('video'); video.src = url; video.controls = true; video.autoplay = true; video.preload = 'metadata'; content.appendChild(video);
                                                } else if (type === 'text') {
                                                    try { const response = await fetch(url); const text = await response.text(); const pre = document.createElement('pre'); pre.className = 'text-preview'; pre.innerText = text; content.appendChild(pre); } catch (e) { content.innerHTML = '<p style="color:white">Error loading text file</p>'; }
                                                } else if (type === 'pdf') {
                                                    const iframe = document.createElement('iframe'); iframe.src = url; content.appendChild(iframe);
                                                }
                                                document.getElementById('preview-title').innerText = name;
                                            }
                                            function closePreview() { 
                                                document.getElementById('preview-content').innerHTML = '';
                                                document.getElementById('preview-overlay').style.display = 'none'; 
                                            }
                                        """.trimIndent()
                                    }
                                }
                                body {
                                    div(classes = "container") {
                                        div {
                                            style = "font-size: 11px; color: #77818d; background: rgba(0,0,0,0.05); padding:  8px 12px; border-radius: 8px; display: inline-block;"
                                            +"Your Device IP: "
                                            span {
                                                style = "color: #007bff; font-weight: bold;"
                                                +call.request.local.remoteHost
                                            }
                                        }
                                        h1 { 
                                            style = "margin-top: 3px; margin-bottom: 3px;"
                                            +"File Transfer" 
                                        }
                                        div(classes = "breadcrumb") {
                                            a(href = "/") { +"🏠 Internal Storage" }
                                            val relativePath = currentDir.absolutePath.removePrefix(root.absolutePath).removePrefix("/")
                                            if (relativePath.isNotEmpty()) {
                                                val parts = relativePath.split("/")
                                                var cumulativePath = ""
                                                parts.forEachIndexed { index, part ->
                                                    span(classes = "separator") { +"❯" }
                                                    cumulativePath += if (cumulativePath.isEmpty()) part else "/$part"
                                                    if (index == parts.size - 1) { span(classes = "current") { +part } } else { a(href = "/?path=${cumulativePath.encodeURLParameter()}") { +part } }
                                                }
                                            }
                                        }
                                        ul {
                                            val files = currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                                            if (files == null) { li { +"⚠️ Permission Denied" } } else {
                                                files.forEach { file ->
                                                    val rel = file.absolutePath.removePrefix(root.absolutePath).removePrefix("/")
                                                    val ext = file.extension.lowercase()
                                                    val isImg = ext in listOf("jpg", "jpeg", "png", "gif", "webp")
                                                    val isVid = ext in listOf("mp4", "mkv", "mov", "avi")
                                                    val isAud = ext in listOf("mp3", "wav", "m4a", "flac")
                                                    val isTxt = ext in listOf("txt", "log", "json", "xml", "kt", "java", "html", "css", "js")
                                                    val isPdf = ext == "pdf"
                                                    val previewType = when { isImg -> "image"; isVid -> "video"; isAud -> "audio"; isTxt -> "text"; isPdf -> "pdf"; else -> null }
                                                    li {
                                                        div(classes = "thumb-container") { if (isImg || isVid || isAud) { img(src = "/thumbnail?path=${rel.encodeURLParameter()}") { onError = "this.src='https://cdn-icons-png.flaticon.com/512/3844/3844724.png'" } } else { span(classes = "icon") { +when { file.isDirectory -> "📁"; isTxt -> "📄"; isPdf -> "📕"; else -> "📄" } } } }
                                                        div(classes = "file-info") { if (file.isDirectory) { a(href = "/?path=${rel.encodeURLParameter()}", classes = "file-name") { +file.name }; div(classes = "file-meta") { +"Folder" } } else { span(classes = "file-name") { if (previewType != null) onClick = "showPreview('/stream?path=${rel.encodeURLParameter()}', '$previewType', '${file.name}', '/thumbnail?path=${rel.encodeURLParameter()}')"; +file.name }; div(classes = "file-meta") { +"${file.length() / 1024} KB • ${file.extension.uppercase()}" } } }
                                                        if (!file.isDirectory) { div(classes = "actions") { if (previewType != null) button(classes = "btn btn-preview") { onClick = "showPreview('/stream?path=${rel.encodeURLParameter()}', '$previewType', '${file.name}', '/thumbnail?path=${rel.encodeURLParameter()}')"; +"Preview" }; a(href = "/download?path=${rel.encodeURLParameter()}", classes = "btn btn-download") { +"Download" } } }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    div { id = "preview-overlay"; span(classes = "close-btn") { onClick = "closePreview()"; +"×" }; h3 { id = "preview-title"; style = "color: white; margin-bottom: 20px;" }; div { id = "preview-content" } }
                                }
                            }
                        } else {
                            // 2. Browser PIN/QR Page (WhatsApp Web Style)
                            val authId = UUID.randomUUID().toString()
                            val userAgent = call.request.headers["User-Agent"] ?: "Unknown"
                            pendingAuthRequests[authId] = userAgent
                            
                            val qrCodeBase64 = try {
                                val bitmap = QRCodeGenerator.generate(authId, 256)
                                val stream = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                Base64.getEncoder().encodeToString(stream.toByteArray())
                            } catch (e: Exception) { "" }

                            val html = """
                                <!DOCTYPE html>
                                <html lang="en">
                                <head>
                                   <meta charset="UTF-8">
                                   <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                   <style>
                                       * { margin: 0; padding: 0; box-sizing: border-box; }
                                       input::selection{background:0 0;color:#4F46E5}
                                       :root { --bg: #e7ebf0; --light: #ffffff; --dark: #b8c0ca; --text: #39424e; --accent: #4F46E5; --success: #16a085; --danger: #e74c3c; }
                                       body { min-height: 100vh; display: flex; justify-content: center; align-items: center; font-family: 'Segoe UI', sans-serif; background: var(--bg); overflow-y: auto; padding: 25px 10px; margin: 0; }
                                       
                                       .electric-wrapper { position: relative; width: 100%; max-width: 420px; padding: 3px; border-radius: 35px; overflow: hidden; margin: auto; box-shadow: 18px 18px 35px rgba(163, 174, 187, .65), -18px -18px 35px rgba(255, 255, 255, .95); }
                                       .electric-wrapper::before { content: ""; position: absolute; inset: -50%; background: conic-gradient(transparent 0deg, transparent 30deg, #4F46E5 55deg, #ffffff 65deg, #00e5ff 75deg, transparent 100deg, transparent 180deg, #7c4dff 210deg, #4F46E5 240deg, transparent 270deg, transparent 360deg); animation: electricSpin 3s linear infinite; }
                                       .electric-wrapper::after { content: ""; position: absolute; inset: 0; border-radius: 35px; box-shadow: 0 0 15px rgba(79, 70, 229, 0.4); pointer-events: none; }
                                       @keyframes electricSpin { to { transform: rotate(360deg); } }

                                       .card { position: relative; z-index: 2; background: var(--bg); border-radius: 32px; padding: 20px 24px; text-align: center; width: 100%; }
                                       .lock {
                                           width: 75px;
                                           height: 75px;
                                           margin: 0 auto 12px;
                                           display: flex;
                                           align-items: center;
                                           justify-content: center;
                                           font-size: 32px;
                                           border-radius: 50%;
                                           background: var(--bg);
                                           box-shadow: inset 7px 7px 13px var(--dark), inset -7px -7px 13px var(--light), 8px 8px 18px rgba(163, 174, 187, .4), -8px -8px 18px rgba(255, 255, 255, .8);
                                           animation: lockPulse 2s ease-in-out infinite;
                                       }
                                       @keyframes lockPulse {
                                           0%, 100% { transform: translateY(0); }
                                           50% { transform: translateY(-5px); }
                                       }

                                       .qr-box { background: white; padding: 15px; border-radius: 15px; display: inline-block; margin-bottom: 0px; box-shadow: inset 5px 5px 10px var(--dark), inset -7px -7px 13px var(--light); }
                                       .qr-box img { width: 180px; height: 180px; display: block; }
                                       
                                       h1 { color: var(--text); font-size: 22px; margin-bottom: 3px; font-weight: 800; }
                                       .desc { color: #77818d; font-size: 14px; margin-bottom: 15px; line-height: 1.5; }
                                       
                                       .divider { height: 1px; background: rgba(0,0,0,0.05); margin: 18px 0; position: relative; }
                                       .divider span { position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); background: var(--bg); padding: 0 15px; font-size: 12px; color: #94A3B8; font-weight: bold; }
                                       
                                       .otp-inputs { display: flex; justify-content: center; gap: 10px; margin: 15px 0; }
                                       .otp-input { width: 45px; height: 55px; border: none; outline: none; text-align: center; font-size: 20px; font-weight: bold; color: var(--text); border-radius: 12px; background: var(--bg); box-shadow: inset 5px 5px 9px var(--dark), inset -5px -5px 9px var(--light); transition: .25s ease; }
                                       .otp-input:focus { color: var(--accent); box-shadow: inset 2px 2px 5px var(--dark), inset -2px -2px 5px var(--light), 0 0 0 2px rgba(79, 70, 229, 0.2), 0 0 15px rgba(79, 70, 229, 0.35); transform: translateY(-3px); }
                                       
                                       .btn { width: 100%; height: 50px; border: none; border-radius: 12px; background: var(--bg); color: var(--text); font-size: 14px; font-weight: bold; cursor: pointer; box-shadow: 8px 8px 15px var(--dark), -8px -8px 15px var(--light); transition: .25s ease; }
                                       .btn:hover { color: var(--accent); transform: translateY(-2px); }
                                       
                                       .message { min-height: 20px; margin-top: 15px; font-size: 13px; font-weight: bold; }
                                       .message.error { color: var(--danger); }
                                       .shake { animation: shake .45s ease; }
                                       @keyframes shake { 0%, 100% { transform: translateX(0); } 20% { transform: translateX(-8px); } 40% { transform: translateX(8px); } 60% { transform: translateX(-6px); } 80% { transform: translateX(6px); } }
                                       
                                       .success-ui { display: none; }
                                       .show-success { display: block !important; animation: successAppear .6s cubic-bezier(0.175, 0.885, 0.32, 1.275) forwards; }
                                       @keyframes successAppear { from { opacity: 0; transform: scale(.8) translateY(20px); } to { opacity: 1; transform: scale(1) translateY(0); } }
                                       .success-icon { width: 100px; height: 100px; margin: 0 auto 25px; border-radius: 50%; display: flex; justify-content: center; align-items: center; font-size: 50px; color: var(--success); background: var(--bg); box-shadow: inset 7px 7px 14px var(--dark), inset -7px -7px 14px var(--light), 8px 8px 18px rgba(163, 174, 187, .4), -8px -8px 18px rgba(255, 255, 255, .8); animation: successPop .8s cubic-bezier(.17, .67, .35, 1.4) both; }
                                       @keyframes successPop { 0% { transform: scale(0) rotate(-90deg); opacity: 0; } 70% { transform: scale(1.15) rotate(10deg); opacity: 1; } 100% { transform: scale(1) rotate(0); opacity: 1; } }
                                       .success-ui h2 { color: var(--success); margin-bottom: 10px; font-weight: 800; }
                                       .success-ui p { color: #77818d; font-size: 14px; line-height: 1.6; }
                                   </style>
                                </head>
                                <body>
                                    <div class="electric-wrapper">
                                        <div class="card" id="authCard">
                                            <div id="loginUI">
                                                <div class="lock">🔐</div>
                                                <div style="font-size: 11px; color: #77818d; background: rgba(0,0,0,0.05); padding: 7px 12px; border-radius: 8px; display: inline-block; margin-bottom: 5px;">
                                                   Your Device IP: <span style="color: #4F46E5; font-weight: bold;">${call.request.local.remoteHost}</span>
                                                </div>
                                                <h1>Transfer Authorization</h1>
                                                <p class="desc">Secure authorization required</p>
                                                
                                                <div class="divider"><span>SCAN TO CONNECT</span></div>
                                                <div class="qr-box">
                                                    <img src="data:image/png;base64,$qrCodeBase64" alt="Scan to Login">
                                                </div>
                                                
                                                <div class="divider"><span>OR ENTER PIN</span></div>
                                                <div class="otp-inputs">
                                                    <input class="otp-input" type="tel" inputmode="numeric" maxlength="1" autofocus onkeypress="return /[0-9]/i.test(event.key)">
                                                    <input class="otp-input" type="tel" inputmode="numeric" maxlength="1" onkeypress="return /[0-9]/i.test(event.key)">
                                                    <input class="otp-input" type="tel" inputmode="numeric" maxlength="1" onkeypress="return /[0-9]/i.test(event.key)">
                                                    <input class="otp-input" type="tel" inputmode="numeric" maxlength="1" onkeypress="return /[0-9]/i.test(event.key)">
                                                </div>
                                                <button class="btn" id="verifyBtn">AUTHORIZE DEVICE</button>
                                                <div class="message" id="message"></div>
                                            </div>
                                            <div class="success-ui" id="successUI">
                                                <div class="success-icon">✓</div>
                                                <h2>Access Granted</h2>
                                                <p>Connection established. Loading your files...</p>
                                            </div>
                                        </div>
                                    </div>
                                    <script>
                                        const authId = '$authId';
                                        const inputs = Array.from(document.querySelectorAll('.otp-input'));
                                        const loginUI = document.getElementById('loginUI');
                                        const successUI = document.getElementById('successUI');
                                        const msg = document.getElementById('message');
                                        const card = document.getElementById('authCard');

                                        // 1. Polling for QR Approval
                                        async function checkStatus() {
                                            try {
                                                const res = await fetch('/qr-status?id=' + authId);
                                                    if (await res.text() === 'APPROVED') {
                                                        loginUI.style.display = 'none';
                                                        successUI.classList.add('show-success');
                                                        setTimeout(() => window.location.reload(), 50);
                                                    } else {
                                                        setTimeout(checkStatus, 400);
                                                    }
                                                } catch(e) { setTimeout(checkStatus, 1000); }
                                        }
                                        setTimeout(checkStatus, 2000);

                                        // 2. Input UX Logic
                                        inputs.forEach((input, idx) => {
                                            input.addEventListener('focus', (e) => { 
                                                setTimeout(() => { input.select(); input.setSelectionRange(0, 99); }, 10); 
                                            });

                                            input.addEventListener('keydown', (e) => {
                                                if (e.key === 'Backspace' && !input.value && idx > 0) {
                                                    inputs[idx - 1].focus();
                                                }
                                                if (e.key === 'Enter') document.getElementById('verifyBtn').click();
                                            });

                                            input.addEventListener('input', (e) => {
                                                let val = e.target.value.replace(/[^0-9]/g, '');
                                                if (val) {
                                                    e.target.value = val.slice(-1);
                                                    if (idx < inputs.length - 1) inputs[idx + 1].focus();
                                                }
                                            });

                                            input.addEventListener('paste', (e) => {
                                                e.preventDefault();
                                                const pasted = (e.clipboardData || window.clipboardData).getData('text').replace(/[^0-9]/g, '');
                                                pasted.split('').forEach((ch, i) => { if (inputs[idx + i]) inputs[idx + i].value = ch; });
                                                const lastIdx = Math.min(idx + pasted.length - 1, inputs.length - 1);
                                                inputs[lastIdx].focus();
                                            });
                                        });

                                        document.getElementById('verifyBtn').addEventListener('click', async () => {
                                            const pin = inputs.map(i => i.value).join('');
                                            if (pin.length !== 4) { 
                                                msg.innerText = 'Please enter the complete 4-digit PIN.'; 
                                                msg.className = 'message error'; 
                                                return; 
                                            }
                                            try {
                                                const res = await fetch('/verify', { method: 'POST', body: 'pin=' + pin, headers: {'Content-Type': 'application/x-www-form-urlencoded'} });
                                                if (await res.text() === 'OK') {
                                                    loginUI.style.display = 'none';
                                                    successUI.classList.add('show-success');
                                                    setTimeout(() => window.location.reload(), 100);
                                                } else {
                                                    msg.innerText = 'Invalid PIN. Please try again.'; 
                                                    msg.className = 'message error'; 
                                                    card.classList.add('shake'); 
                                                    setTimeout(() => card.classList.remove('shake'), 500); 
                                                }
                                            } catch(e) { msg.innerText = 'Server Error'; msg.className = 'message error'; }
                                        });
                                    </script>
                                </body>
                                </html>
                            """.trimIndent()
                            call.respondText(html, ContentType.Text.Html)
                        }
                    }

                    get("/qr-status") {
                        val id = call.request.queryParameters["id"] ?: return@get call.respondText("MISSING")
                        val status = pendingAuthRequests[id] ?: "PENDING"
                        if (status == "APPROVED") {
                            // Convert pending to active session
                            val clientIP = call.request.local.remoteHost
                            val userAgent = call.request.headers["User-Agent"] ?: "Unknown"
                            activeSessions[id] = parseDeviceInfo(userAgent, clientIP)
                            saveSessions(context)
                            call.sessions.set(UserSession(id))
                            pendingAuthRequests.remove(id)
                            call.respondText("APPROVED")
                        } else {
                            call.respondText("PENDING")
                        }
                    }

                    get("/approve-qr") {
                        val id = call.request.queryParameters["id"] ?: return@get call.respondText("FAIL")
                        if (approveAuthRequest(id)) call.respondText("OK") else call.respondText("FAIL")
                    }

                    post("/verify") {
                        val params = call.receiveParameters()
                        val pin = params["pin"]
                        if (pin == currentPin) {
                            val token = UUID.randomUUID().toString()
                            val clientIP = call.request.local.remoteHost
                            val userAgent = call.request.headers["User-Agent"] ?: "Unknown"
                            
                            activeSessions[token] = parseDeviceInfo(userAgent, clientIP)
                            saveSessions(context)
                            notifyDevices()
                            call.sessions.set(UserSession(token))
                            call.respondText("OK")
                        } else {
                            call.respondText("FAIL", status = HttpStatusCode.Unauthorized)
                        }
                    }

                    get("/thumbnail") {
                        val session = call.sessions.get<UserSession>()
                        if (session == null || !activeSessions.containsKey(session.token)) return@get call.respond(HttpStatusCode.Forbidden)
                        val root = Environment.getExternalStorageDirectory()
                        val path = call.parameters["path"] ?: return@get call.respondText("Missing path")
                        val file = File(root, path.removePrefix("/"))
                        if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound)

                        val ext = file.extension.lowercase()
                        val bitmap: Bitmap? = try {
                            when {
                                ext in listOf("jpg", "jpeg", "png", "gif", "webp") -> {
                                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeFile(file.absolutePath, options)
                                    options.inSampleSize = 4
                                    options.inJustDecodeBounds = false
                                    BitmapFactory.decodeFile(file.absolutePath, options)
                                }
                                ext in listOf("mp4", "mkv", "mov", "avi") -> {
                                    val retriever = MediaMetadataRetriever()
                                    try {
                                        retriever.setDataSource(file.absolutePath)
                                        retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    } finally { retriever.release() }
                                }
                                ext in listOf("mp3", "wav", "m4a", "flac") -> {
                                    val retriever = MediaMetadataRetriever()
                                    try {
                                        retriever.setDataSource(file.absolutePath)
                                        val art = retriever.embeddedPicture
                                        if (art != null) BitmapFactory.decodeByteArray(art, 0, art.size) else null
                                    } finally { retriever.release() }
                                }
                                else -> null
                            }
                        } catch (e: Exception) { null }

                        if (bitmap != null) {
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                            call.respondBytes(stream.toByteArray(), ContentType.Image.JPEG)
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    }

                    get("/stream") {
                        val session = call.sessions.get<UserSession>()
                        if (session == null || !activeSessions.containsKey(session.token)) return@get call.respond(HttpStatusCode.Forbidden)
                        val root = Environment.getExternalStorageDirectory()
                        val path = call.parameters["path"] ?: return@get call.respondText("Missing path")
                        val file = File(root, path.removePrefix("/"))
                        if (file.exists() && !file.isDirectory) call.respondFile(file) else call.respondText("File not found", status = HttpStatusCode.NotFound)
                    }

                    get("/download") {
                        val session = call.sessions.get<UserSession>()
                        if (session == null || !activeSessions.containsKey(session.token)) return@get call.respond(HttpStatusCode.Forbidden)
                        val root = Environment.getExternalStorageDirectory()
                        val path = call.parameters["path"] ?: return@get call.respondText("Missing path")
                        val file = File(root, path.removePrefix("/"))
                        if (file.exists() && !file.isDirectory) { call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.name).toString()); call.respondFile(file) } else call.respondText("File not found", status = HttpStatusCode.NotFound)
                    }
                }
            }
            server = newServer
            newServer.start(wait = false)
            val ip = getLocalIpAddress()
            lastUrl = "http://$ip:$port"
            onStarted(lastUrl!!, currentPin)
            notifyDevices() // Final sync
        } catch (e: Exception) { Log.e("FileServer", "Error starting server", e); throw e }
    }

    private fun parseDeviceInfo(userAgent: String, clientIP: String): String {

        // 1. OS Extraction
        val osMatch = Regex("\\(([^)]+)\\)").find(userAgent)
        val osRaw = osMatch?.groupValues?.get(1) ?: "Unknown OS"
        val cleanOS = osRaw.split(";").map { it.trim() }
            .filter { part ->
                val p = part.lowercase()
                p.length > 2 && !p.contains("win64") && !p.contains("x64") && !p.contains("wow64") &&
                !p.contains("rv:") && !p.equals("k") && !p.equals("u") &&
                !p.matches(Regex("[a-z]{2}-[a-z]{2}")) && 
                !(p.equals("linux") && userAgent.contains("Android", true))
            }.map { part ->
                when {
                    part.contains("Windows NT 10.0") -> "Windows 10/11"
                    part.contains("Windows NT 6.3") -> "Windows 8.1"
                    part.contains("Windows NT 6.2") -> "Windows 8"
                    part.contains("Windows NT 6.1") -> "Windows 7"
                    else -> part
                }
            }.joinToString("; ")

        // 2. Browser Detection
        val (bName, bVer) = when {
            userAgent.contains("Edg") -> "Edge" to (Regex("Edg[A]?/([^ ]+)").find(userAgent)?.groupValues?.get(1) ?: "?")
            userAgent.contains("OPR") || userAgent.contains("Opera") -> "Opera" to (Regex("(?:OPR|Opera)/([^ ]+)").find(userAgent)?.groupValues?.get(1) ?: "?")
            userAgent.contains("UCBrowser") -> "UCBrowser" to (Regex("UCBrowser/([^ ]+)").find(userAgent)?.groupValues?.get(1) ?: "?")
            userAgent.contains("Firefox") -> "Firefox" to (Regex("Firefox/([^ ]+)").find(userAgent)?.groupValues?.get(1) ?: "?")
            userAgent.contains("Chrome") -> "Chrome" to (Regex("Chrome/([^ ]+)").find(userAgent)?.groupValues?.get(1) ?: "?")
            userAgent.contains("Safari") -> "Safari" to (Regex("Version/([^ ]+)").find(userAgent)?.groupValues?.get(1) ?: "?")
            else -> "Browser" to "?"
        }
        
        val cleanVer = bVer.substringBefore(".")
        Log.d("FileServer", "SpikE => $userAgent")
        return "$cleanOS|$bName|$cleanVer|$clientIP"
    }

    fun getLocalIpAddress(): String? {
         try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is InetAddress && !address.hostAddress!!.contains(":")) return address.hostAddress!!
                }
            }
        } catch (ex: Exception) { }
        return null
    }
}
fun String.encodeURLParameter(): String = URLEncoder.encode(this, "UTF-8")
