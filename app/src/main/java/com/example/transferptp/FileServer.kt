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
import android.util.LruCache
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
import io.ktor.util.reflect.typeInfo
import kotlinx.html.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class UserSession(val token: String)

private const val THUMBNAIL_SIZE = 256
private val THUMBNAIL_CONTENT_TYPE = ContentType("image", "webp")

private class ThumbnailCache(private val directory: File) {
    private val memoryCache = object : LruCache<String, ByteArray>(MAX_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    @Synchronized
    fun get(key: String): ByteArray? {
        val diskFile = fileFor(key)
        memoryCache.get(key)?.let { bytes ->
            diskFile.setLastModified(System.currentTimeMillis())
            return bytes
        }

        if (!diskFile.isFile) return null
        return try {
            val bytes = diskFile.readBytes()
            diskFile.setLastModified(System.currentTimeMillis())
            memoryCache.put(key, bytes)
            bytes
        } catch (e: Exception) {
            diskFile.delete()
            null
        }
    }

    @Synchronized
    fun put(key: String, bytes: ByteArray) {
        memoryCache.put(key, bytes)
        try {
            if (!directory.exists() && !directory.mkdirs()) return

            val target = fileFor(key)
            val temporary = File(directory, "${target.name}.tmp")
            temporary.outputStream().use { it.write(bytes) }
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            target.setLastModified(System.currentTimeMillis())
            trimDiskCache()
        } catch (e: Exception) {
            File(directory, "${fileNameFor(key)}.webp.tmp").delete()
        }
    }

    private fun trimDiskCache() {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "webp" } ?: return
        var totalBytes = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (totalBytes <= MAX_DISK_BYTES) break
            val fileLength = file.length()
            if (file.delete()) totalBytes -= fileLength
        }
    }

    private fun fileFor(key: String): File = File(directory, "${fileNameFor(key)}.webp")

    private fun fileNameFor(key: String): String = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    companion object {
        private const val MAX_MEMORY_BYTES = 15 * 1024 * 1024
        private const val MAX_DISK_BYTES = 128L * 1024 * 1024
    }
}

class FileServer(private val context: Context) {
    
    private var currentPin: String = ""
    private val thumbnailCache by lazy {
        ThumbnailCache(File(context.cacheDir, "thumbnail_cache"))
    }
    
    companion object {
        private var server: EmbeddedServer<*, *>? = null
        private val activeSessions = ConcurrentHashMap<String, String>() 
        private val pendingAuthRequests = ConcurrentHashMap<String, String>() // ID -> UserAgent
        
        private var onDevicesUpdated: ((List<String>) -> Unit)? = null
        private var lastUrl: String? = null
        private var lastPin: String? = null
        private var serverSecret: String = ""

        private fun isEtagMatch(header: String?, targetEtag: String): Boolean {
            if (header == null) return false
            val cleanTarget = targetEtag.removePrefix("W/").trim(' ', '"')
            return header.split(",").any { tag ->
                val cleanTag = tag.trim().removePrefix("W/").trim(' ', '"')
                cleanTag == "*" || cleanTag == cleanTarget
            }
        }

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
                intercept(ApplicationCallPipeline.Plugins) {
                    ServerService.notifyRequestReceived(this@FileServer.context)
                }
                install(Sessions) {
                    cookie<UserSession>("USER_SESSION", typeInfo<UserSession>()) {
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
                                            .thumb-container img.ph { background-color: #f0f0f0; background-position: center; background-repeat: no-repeat; background-size: contain; }
                                            .thumb-container img.ph-image { background-image: url('$FALLBACK_IMAGE_SVG'); }
                                            .thumb-container img.ph-video { background-image: url('$FALLBACK_VIDEO_SVG'); }
                                            .thumb-container img.ph-audio { background-image: url('$FALLBACK_AUDIO_SVG'); }
                                            .thumb-container img.ph.done { background-image: none; }
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
                                            function openPreview(elem) {
                                                const url = elem.getAttribute('data-url');
                                                const type = elem.getAttribute('data-type');
                                                const name = elem.getAttribute('data-name');
                                                const thumbUrl = elem.getAttribute('data-thumb');
                                                showPreview(url, type, name, thumbUrl);
                                            }

                                            async function showPreview(url, type, name, thumbUrl) {
                                                const overlay = document.getElementById('preview-overlay');
                                                const content = document.getElementById('preview-content');
                                                overlay.style.display = 'flex';
                                                content.innerHTML = '';
                                                if (type === 'image') {
                                                    const img = document.createElement('img');
                                                    img.src = url;
                                                    img.onerror = function() { if (thumbUrl) this.src = thumbUrl; };
                                                    img.style.maxWidth = '100%';
                                                    img.style.maxHeight = '100%';
                                                    content.appendChild(img);
                                                } else if (type === 'audio') {
                                                    const art = document.createElement('img'); art.src = thumbUrl; art.className = 'music-art'; art.onerror = function() { this.src = '$FALLBACK_AUDIO_SVG'; }; content.appendChild(art);
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

                                            function loadViewportImages() {
                                                var lazyImages = document.querySelectorAll('img[data-src]');

                                                if (!lazyImages || !lazyImages.length) return;

                                                if ('IntersectionObserver' in window) {
                                                    var observer = new IntersectionObserver(function(entries, obs) {
                                                        entries.forEach(function(entry) {
                                                            if (entry.isIntersecting) {
                                                                var img = entry.target;
                                                                var realSrc = img.getAttribute('data-src');
                                                                if (realSrc) {
                                                                    img.src = realSrc;
                                                                    img.removeAttribute('data-src');
                                                                }
                                                                obs.unobserve(img);
                                                            }
                                                        });
                                                    }, {
                                                        root: null,
                                                        rootMargin: '100px 0px 350px 0px', // Strict screen viewport + ~5 items buffer below
                                                        threshold: 0.01
                                                    });

                                                    lazyImages.forEach(function(img) { observer.observe(img); });
                                                } else {
                                                    lazyImages.forEach(function(img) {
                                                        var realSrc = img.getAttribute('data-src');
                                                        if (realSrc) {
                                                            img.src = realSrc;
                                                            img.removeAttribute('data-src');
                                                        }
                                                    });
                                                }
                                            }

                                            if (document.readyState === 'loading') {
                                                document.addEventListener('DOMContentLoaded', loadViewportImages);
                                            } else {
                                                loadViewportImages();
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
                                                    val encodedRel = rel.encodeURLParameter()
                                                    val fileVer = "${file.lastModified()}_${file.length()}_webp256"
                                                    val thumbUrl = "/thumbnail?path=$encodedRel&v=$fileVer"
                                                    val streamUrl = "/stream?path=$encodedRel&v=$fileVer"
                                                    val downloadUrl = "/download?path=$encodedRel"
                                                    val fallbackSvg = when {
                                                        isAud -> FALLBACK_AUDIO_SVG
                                                        isVid -> FALLBACK_VIDEO_SVG
                                                        isImg -> FALLBACK_IMAGE_SVG
                                                        else -> ""
                                                    }
                                                    li {
                                                        div(classes = "thumb-container") {
                                                            if (isImg || isVid || isAud) {
                                                                val initialSrc = fallbackSvg.ifEmpty { "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1 1'%3E%3C/svg%3E" }
                                                                img(src = initialSrc) {
                                                                    attributes["data-src"] = thumbUrl
                                                                    onLoad = "if(this.src&&!this.src.startsWith('data:'))this.style.background='none';"
                                                                    onError = "this.onerror=null;this.style.background='none';this.src='$fallbackSvg';"
                                                                    if (fallbackSvg.isNotEmpty()) {
                                                                        style = "background: url('$fallbackSvg') center/contain no-repeat #f0f0f0;"
                                                                    }
                                                                }
                                                            } else {
                                                                span(classes = "icon") {
                                                                    +when {
                                                                        file.isDirectory -> "📁"
                                                                        isTxt -> "📄"
                                                                        isPdf -> "📕"
                                                                        else -> "📄"
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        div(classes = "file-info") { if (file.isDirectory) { a(href = "/?path=$encodedRel", classes = "file-name") { +file.name }; div(classes = "file-meta") { +"Folder" } } else { span(classes = "file-name") { if (previewType != null) { attributes["data-url"] = streamUrl; attributes["data-type"] = previewType; attributes["data-name"] = file.name; attributes["data-thumb"] = thumbUrl; onClick = "openPreview(this)"; }; +file.name }; div(classes = "file-meta") { +"${file.length() / 1024} KB • ${file.extension.uppercase()}" } } }
                                                        if (!file.isDirectory) { div(classes = "actions") { if (previewType != null) button(classes = "btn btn-preview") { attributes["data-url"] = streamUrl; attributes["data-type"] = previewType; attributes["data-name"] = file.name; attributes["data-thumb"] = thumbUrl; onClick = "openPreview(this)"; +"Preview" }; a(href = downloadUrl, classes = "btn btn-download") { +"Download" } } }
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

                        val etag = "\"thumb-webp256-${file.lastModified()}-${file.length()}\""
                        val clientEtag = call.request.headers[HttpHeaders.IfNoneMatch]
                        val vParam = call.request.queryParameters["v"]

                        if (vParam != null) {
                            call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                        } else {
                            call.response.header(HttpHeaders.CacheControl, "no-cache")
                        }
                        call.response.header(HttpHeaders.ETag, etag)

                        if (isEtagMatch(clientEtag, etag)) {
                            return@get call.respond(HttpStatusCode.NotModified)
                        }

                        val cacheKey = "webp256-rgb565:${file.absolutePath}:${file.lastModified()}:${file.length()}"
                        val cachedBytes = thumbnailCache.get(cacheKey)
                        if (cachedBytes != null) {
                            return@get call.respondBytes(cachedBytes, THUMBNAIL_CONTENT_TYPE)
                        }

                        val bytes = renderThumbnailBytes(file)
                        if (bytes != null) {
                            thumbnailCache.put(cacheKey, bytes)
                            call.respondBytes(bytes, THUMBNAIL_CONTENT_TYPE)
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
                        if (file.exists() && !file.isDirectory) {
                            val etag = "\"stream-${file.lastModified()}-${file.length()}\""
                            val clientEtag = call.request.headers[HttpHeaders.IfNoneMatch]
                            val vParam = call.request.queryParameters["v"]

                            if (vParam != null) {
                                call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                            } else {
                                call.response.header(HttpHeaders.CacheControl, "no-cache")
                            }
                            call.response.header(HttpHeaders.ETag, etag)

                            if (isEtagMatch(clientEtag, etag)) {
                                return@get call.respond(HttpStatusCode.NotModified)
                            }
                            call.respondFile(file)
                        } else {
                            call.respondText("File not found", status = HttpStatusCode.NotFound)
                        }
                    }

                    get("/download") {
                        val session = call.sessions.get<UserSession>()
                        if (session == null || !activeSessions.containsKey(session.token)) return@get call.respond(HttpStatusCode.Forbidden)
                        val root = Environment.getExternalStorageDirectory()
                        val path = call.parameters["path"] ?: return@get call.respondText("Missing path")
                        val file = File(root, path.removePrefix("/"))
                        if (file.exists() && !file.isDirectory) {
                            val etag = "\"dl-${file.lastModified()}-${file.length()}\""
                            val clientEtag = call.request.headers[HttpHeaders.IfNoneMatch]

                            call.response.header(HttpHeaders.CacheControl, "no-cache")
                            call.response.header(HttpHeaders.ETag, etag)
                            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.name).toString())

                            if (isEtagMatch(clientEtag, etag)) {
                                return@get call.respond(HttpStatusCode.NotModified)
                            }
                            call.respondFile(file)
                        } else call.respondText("File not found", status = HttpStatusCode.NotFound)
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

    private fun renderThumbnailBytes(file: File): ByteArray? {
        val bitmap = createThumbnail(file) ?: return null
        return try {
            ByteArrayOutputStream().use { stream ->
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
                if (bitmap.compress(format, 80, stream)) stream.toByteArray() else null
            }
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun createThumbnail(file: File): Bitmap? {
        return try {
            when (file.extension.lowercase()) {
                "jpg", "jpeg", "png", "gif", "webp" -> decodeImageThumbnail(file)
                "mp4", "mkv", "mov", "avi" -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        retriever.getScaledFrameAtTime(
                            1_000_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            THUMBNAIL_SIZE,
                            THUMBNAIL_SIZE
                        )?.let(::prepareThumbnail)
                    } finally {
                        retriever.release()
                    }
                }
                "mp3", "wav", "m4a", "flac" -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        retriever.embeddedPicture?.let(::decodeArtworkThumbnail)
                    } finally {
                        retriever.release()
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeImageThumbnail(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)?.let(::prepareThumbnail)
    }

    private fun decodeArtworkThumbnail(artwork: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(artwork, 0, artwork.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(artwork, 0, artwork.size, options)?.let(::prepareThumbnail)
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        val largestDimension = maxOf(width, height)
        while (largestDimension / (sampleSize * 2) >= THUMBNAIL_SIZE) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun prepareThumbnail(source: Bitmap): Bitmap {
        var bitmap = source
        try {
            val largestDimension = maxOf(bitmap.width, bitmap.height)
            if (largestDimension > THUMBNAIL_SIZE) {
                val scale = THUMBNAIL_SIZE.toFloat() / largestDimension
                val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }

            if (bitmap.config != Bitmap.Config.RGB_565) {
                val rgb565 = bitmap.copy(Bitmap.Config.RGB_565, false)
                if (rgb565 != null) {
                    bitmap.recycle()
                    bitmap = rgb565
                }
            }
            return bitmap
        } catch (e: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            throw e
        }
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

private object FallbackIcons {
    val AUDIO: String by lazy {
        "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(AUDIO_SVG.trimIndent().toByteArray(Charsets.UTF_8))
    }
    val VIDEO: String by lazy {
        "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(VIDEO_SVG.trimIndent().toByteArray(Charsets.UTF_8))
    }
    val IMAGE: String by lazy {
        "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(IMAGE_SVG.trimIndent().toByteArray(Charsets.UTF_8))
    }

    private const val AUDIO_SVG = """<svg version="1.1" xmlns="http://www.w3.org/2000/svg" width="512" height="512">
<path d="M0 0 C1.21 -0 2.42 -0.01 3.67 -0.01 C6.99 -0.02 10.3 -0.01 13.62 -0.01 C17.21 -0 20.81 -0.01 24.4 -0.02 C31.42 -0.02 38.44 -0.02 45.47 -0.02 C51.18 -0.01 56.89 -0.01 62.6 -0.01 C63.83 -0.01 63.83 -0.01 65.07 -0.01 C66.73 -0.02 68.39 -0.02 70.05 -0.02 C85.55 -0.02 101.06 -0.02 116.57 -0 C129.86 0 143.14 0 156.42 -0.01 C171.88 -0.02 187.33 -0.02 202.79 -0.02 C204.44 -0.01 206.09 -0.01 207.74 -0.01 C208.96 -0.01 208.96 -0.01 210.21 -0.01 C215.9 -0.01 221.6 -0.01 227.3 -0.02 C234.26 -0.02 241.21 -0.02 248.16 -0.01 C251.71 -0.01 255.25 -0 258.79 -0.01 C262.64 -0.02 266.49 -0.01 270.35 0 C272 -0.01 272 -0.01 273.68 -0.01 C284.44 0.04 295.23 1.25 305.55 4.38 C306.25 4.59 306.96 4.8 307.68 5.02 C335.67 13.92 358.02 34.11 371.42 60 C374.13 65.48 376.29 71.03 378.05 76.88 C378.24 77.51 378.43 78.14 378.63 78.79 C381.65 89.67 382.47 100.85 382.43 112.08 C382.43 113.29 382.43 114.5 382.44 115.75 C382.44 119.07 382.44 122.38 382.43 125.7 C382.43 129.29 382.44 132.89 382.44 136.48 C382.45 143.5 382.45 150.52 382.44 157.55 C382.44 163.26 382.44 168.97 382.44 174.68 C382.44 175.5 382.44 176.31 382.44 177.15 C382.44 178.81 382.44 180.47 382.44 182.13 C382.45 197.64 382.44 213.14 382.43 228.65 C382.42 241.94 382.42 255.22 382.43 268.5 C382.44 283.96 382.45 299.42 382.44 314.87 C382.44 316.52 382.44 318.17 382.44 319.82 C382.44 320.64 382.44 321.45 382.44 322.29 C382.44 327.99 382.44 333.68 382.45 339.38 C382.45 346.34 382.45 353.29 382.44 360.24 C382.43 363.79 382.43 367.33 382.44 370.87 C382.45 374.72 382.44 378.57 382.43 382.43 C382.43 383.53 382.44 384.63 382.44 385.76 C382.39 396.52 381.17 407.31 378.05 417.63 C377.84 418.33 377.62 419.04 377.4 419.76 C368.51 447.75 348.31 470.1 322.42 483.5 C316.95 486.21 311.4 488.37 305.55 490.13 C304.92 490.32 304.29 490.51 303.64 490.71 C292.76 493.73 281.58 494.55 270.35 494.51 C269.14 494.51 267.93 494.51 266.68 494.52 C263.36 494.52 260.05 494.52 256.73 494.52 C253.14 494.51 249.54 494.52 245.95 494.52 C238.92 494.53 231.9 494.53 224.88 494.52 C219.17 494.52 213.46 494.52 207.74 494.52 C206.93 494.52 206.11 494.52 205.27 494.52 C203.62 494.52 201.96 494.52 200.3 494.52 C184.79 494.53 169.28 494.52 153.77 494.51 C140.49 494.5 127.21 494.51 113.93 494.51 C98.47 494.53 83.01 494.53 67.55 494.52 C65.9 494.52 64.25 494.52 62.6 494.52 C61.38 494.52 61.38 494.52 60.14 494.52 C54.44 494.52 48.74 494.52 43.04 494.53 C36.09 494.53 29.14 494.53 22.18 494.52 C18.64 494.51 15.1 494.51 11.56 494.52 C7.7 494.53 3.85 494.52 0 494.51 C-1.1 494.51 -2.2 494.52 -3.34 494.52 C-14.09 494.47 -24.88 493.25 -35.2 490.13 C-35.91 489.92 -36.61 489.7 -37.34 489.49 C-65.33 480.59 -87.67 460.39 -101.08 434.5 C-103.78 429.03 -105.94 423.48 -107.7 417.63 C-107.89 417 -108.08 416.37 -108.28 415.72 C-111.3 404.84 -112.13 393.66 -112.08 382.43 C-112.08 381.22 -112.09 380.01 -112.09 378.76 C-112.1 375.44 -112.09 372.13 -112.09 368.81 C-112.08 365.22 -112.09 361.62 -112.1 358.03 C-112.1 351.01 -112.1 343.98 -112.1 336.96 C-112.09 331.25 -112.09 325.54 -112.09 319.82 C-112.09 319.01 -112.09 318.19 -112.1 317.35 C-112.1 315.7 -112.1 314.04 -112.1 312.38 C-112.1 296.87 -112.1 281.36 -112.09 265.85 C-112.08 252.57 -112.08 239.29 -112.09 226.01 C-112.1 210.55 -112.1 195.09 -112.1 179.64 C-112.1 177.98 -112.09 176.33 -112.09 174.68 C-112.09 173.87 -112.09 173.06 -112.09 172.22 C-112.09 166.52 -112.09 160.82 -112.1 155.12 C-112.1 148.17 -112.1 141.22 -112.09 134.26 C-112.09 130.72 -112.09 127.18 -112.09 123.64 C-112.1 119.79 -112.09 115.93 -112.08 112.08 C-112.09 110.98 -112.09 109.88 -112.09 108.74 C-112.04 97.99 -110.83 87.2 -107.7 76.88 C-107.49 76.17 -107.28 75.47 -107.06 74.74 C-98.16 46.75 -77.97 24.41 -52.08 11 C-46.6 8.3 -41.05 6.14 -35.2 4.38 C-34.57 4.19 -33.94 4 -33.29 3.8 C-22.42 0.78 -11.23 -0.05 0 0 Z " fill="#F86067" transform="translate(120.82666015625,8.74603271484375)"/>
<path d="M0 0 C12.37 6.75 12.37 6.75 16.95 9.59 C17.97 10.22 18.99 10.85 20.04 11.5 C21.1 12.16 22.16 12.82 23.25 13.5 C25.56 14.93 27.86 16.35 30.17 17.78 C30.75 18.14 31.33 18.5 31.92 18.86 C37.43 22.27 42.96 25.64 48.5 29 C50.37 30.14 52.24 31.28 54.11 32.42 C57.76 34.65 61.43 36.86 65.11 39.06 C67.44 40.46 69.78 41.87 72.11 43.28 C73.75 44.28 75.4 45.25 77.05 46.23 C86.49 51.97 95.4 58.02 100.06 68.31 C100.61 69.48 101.16 70.66 101.72 71.86 C105.33 80.71 105.14 89.84 101.88 98.81 C97.63 108.7 90.86 117.21 81 122 C80.34 121.67 79.68 121.34 79 121 C79.48 119.95 79.48 119.95 79.97 118.89 C82.43 111.99 81.99 104.21 79 97.56 C74.77 89.12 64.25 84.54 56.51 79.82 C54.74 78.74 52.97 77.66 51.2 76.57 C44.72 72.58 44.72 72.58 38 69 C38 69.76 38 70.52 38.01 71.3 C38.05 89.79 38.08 108.28 38.1 126.76 C38.11 135.7 38.13 144.65 38.15 153.59 C38.17 161.38 38.18 169.18 38.19 176.97 C38.19 181.1 38.2 185.22 38.21 189.35 C38.23 193.24 38.23 197.13 38.23 201.02 C38.23 202.44 38.23 203.86 38.24 205.28 C38.35 227.3 31.47 246.1 16 262 C15.42 262.64 14.85 263.28 14.25 263.93 C2.38 276.72 -14.84 283.29 -31.96 284.21 C-52.56 284.87 -70.26 279.71 -86 266 C-86.6 265.54 -87.2 265.09 -87.81 264.62 C-101.53 253.9 -109.13 235.8 -112 219 C-114.25 199.2 -108.45 179.91 -96.5 164 C-95.36 162.65 -94.19 161.31 -93 160 C-92.15 159.01 -92.15 159.01 -91.28 158 C-78.53 144.14 -59.51 135.89 -40.89 134.68 C-26.15 134.1 -14.5 137.86 -1 144 C-0.67 96.48 -0.34 48.96 0 0 Z " fill="#FEFEFE" transform="translate(260,114)"/>
<path d="M0 0 C7.58 4.43 13.61 10.59 17.5 18.46 C17.81 19.06 18.13 19.66 18.46 20.28 C22.24 28.58 21.41 37.78 18.5 46.21 C13.88 56.97 6.13 63.95 -4.63 68.27 C-14.49 71.23 -24.49 70.29 -33.5 65.46 C-43.72 59.65 -49.29 51.58 -52.5 40.46 C-53.94 28.76 -52.3 20.27 -45.5 10.46 C-34.23 -2.47 -15.92 -6.86 0 0 Z " fill="#F86168" transform="translate(239.50390625,290.54296875)"/>
</svg>"""

    private const val VIDEO_SVG = """<svg version="1.1" xmlns="http://www.w3.org/2000/svg" width="512" height="512">
<path d="M0 0 C1.75 -0 3.5 -0.01 5.25 -0.02 C10.04 -0.03 14.83 -0.03 19.62 -0.02 C24.8 -0.01 29.97 -0.03 35.15 -0.04 C45.28 -0.05 55.41 -0.06 65.54 -0.05 C73.78 -0.05 82.01 -0.05 90.25 -0.05 C92 -0.05 92 -0.05 93.8 -0.05 C96.18 -0.06 98.56 -0.06 100.94 -0.06 C123.28 -0.07 145.61 -0.07 167.94 -0.06 C188.37 -0.05 208.79 -0.06 229.22 -0.08 C250.2 -0.11 271.18 -0.12 292.16 -0.11 C303.93 -0.11 315.71 -0.11 327.48 -0.13 C337.51 -0.14 347.53 -0.14 357.56 -0.12 C362.67 -0.12 367.78 -0.11 372.9 -0.13 C377.58 -0.14 382.27 -0.14 386.95 -0.12 C388.64 -0.12 390.33 -0.12 392.02 -0.13 C406.25 -0.21 418.11 2.17 428.83 12.2 C438.42 22.45 441.21 34.07 441.16 47.74 C441.16 48.74 441.16 49.74 441.17 50.78 C441.18 54.12 441.17 57.47 441.17 60.82 C441.17 63.22 441.18 65.63 441.19 68.04 C441.2 73.89 441.2 79.73 441.2 85.58 C441.2 90.34 441.2 95.09 441.2 99.85 C441.2 100.53 441.2 101.21 441.2 101.91 C441.2 103.28 441.21 104.66 441.21 106.04 C441.22 118.95 441.22 131.86 441.21 144.77 C441.2 156.56 441.22 168.36 441.23 180.15 C441.25 192.28 441.26 204.41 441.26 216.54 C441.26 223.34 441.26 230.14 441.27 236.94 C441.29 243.34 441.28 249.74 441.27 256.13 C441.27 258.48 441.27 260.82 441.28 263.16 C441.29 266.37 441.28 269.58 441.27 272.79 C441.28 273.71 441.28 274.62 441.29 275.57 C441.18 288.58 436.71 299.95 427.91 309.54 C416.71 319.84 404.19 320.83 389.79 320.78 C388.04 320.78 386.29 320.79 384.54 320.79 C379.75 320.81 374.96 320.8 370.17 320.79 C364.99 320.79 359.82 320.8 354.64 320.81 C344.51 320.83 334.38 320.83 324.25 320.83 C316.01 320.82 307.78 320.82 299.54 320.83 C297.79 320.83 297.79 320.83 295.99 320.83 C293.61 320.83 291.23 320.83 288.85 320.83 C266.51 320.85 244.18 320.84 221.85 320.83 C201.42 320.82 181 320.83 160.57 320.86 C139.59 320.88 118.61 320.89 97.63 320.89 C85.86 320.88 74.08 320.88 62.31 320.9 C52.28 320.92 42.26 320.92 32.23 320.9 C27.12 320.89 22.01 320.89 16.89 320.9 C12.21 320.92 7.52 320.91 2.84 320.89 C1.15 320.89 -0.54 320.89 -2.23 320.9 C-16.46 320.99 -28.32 318.6 -39.04 308.58 C-48.63 298.33 -51.42 286.71 -51.37 273.04 C-51.37 272.04 -51.37 271.03 -51.38 270 C-51.39 266.65 -51.38 263.3 -51.38 259.96 C-51.38 257.55 -51.39 255.14 -51.4 252.74 C-51.41 246.89 -51.41 241.04 -51.41 235.19 C-51.41 230.44 -51.41 225.68 -51.41 220.93 C-51.41 220.25 -51.41 219.57 -51.41 218.87 C-51.41 217.49 -51.42 216.11 -51.42 214.73 C-51.43 201.82 -51.43 188.92 -51.42 176.01 C-51.41 164.21 -51.43 152.42 -51.44 140.63 C-51.46 128.5 -51.47 116.37 -51.47 104.24 C-51.47 97.44 -51.47 90.64 -51.48 83.83 C-51.5 77.44 -51.49 71.04 -51.48 64.64 C-51.48 62.3 -51.48 59.96 -51.49 57.61 C-51.5 54.4 -51.49 51.2 -51.48 47.99 C-51.49 47.07 -51.49 46.15 -51.5 45.2 C-51.39 32.19 -46.92 20.82 -38.12 11.24 C-26.92 0.94 -14.4 -0.06 0 0 Z " fill="#D53740" transform="translate(61.104949951171875,63.61231994628906)"/>
<path d="M0 0 C1.09 0.61 2.18 1.23 3.3 1.86 C12.7 7.17 22.03 12.6 31.34 18.06 C37.69 21.78 44.06 25.45 50.44 29.12 C53.68 31 56.93 32.87 60.17 34.74 C62.38 36.01 64.58 37.28 66.79 38.55 C70.05 40.43 73.31 42.31 76.56 44.19 C77.54 44.75 78.52 45.31 79.52 45.88 C97.29 56.18 97.29 56.18 100.44 65.62 C101.38 72.76 100.64 78.32 96.54 84.31 C92.87 88.35 88.49 90.76 83.73 93.36 C81.04 94.84 78.38 96.38 75.72 97.93 C70.71 100.85 65.68 103.74 60.65 106.63 C54.57 110.12 48.5 113.62 42.44 117.12 C40.28 118.37 38.12 119.61 35.96 120.86 C34.89 121.48 33.82 122.1 32.72 122.73 C29.38 124.66 26.03 126.59 22.69 128.52 C21.03 129.48 21.03 129.48 19.33 130.46 C17.13 131.72 14.94 132.99 12.75 134.25 C7.72 137.15 2.7 140.05 -2.31 142.98 C-3.19 143.49 -4.07 144 -4.98 144.53 C-6.61 145.48 -8.24 146.43 -9.87 147.39 C-10.58 147.81 -11.29 148.22 -12.03 148.65 C-12.64 149.01 -13.26 149.37 -13.89 149.74 C-19.29 152.6 -26.09 152.65 -31.89 150.94 C-38.22 148.03 -42.28 143.29 -44.94 136.83 C-46.28 132.08 -45.99 127.11 -45.97 122.21 C-45.97 120.97 -45.98 119.73 -45.99 118.46 C-46 115.08 -46 111.71 -46 108.33 C-46 105.51 -46 102.68 -46.01 99.86 C-46.02 93.19 -46.02 86.52 -46.01 79.85 C-46 72.98 -46.01 66.12 -46.04 59.25 C-46.06 53.35 -46.07 47.44 -46.06 41.53 C-46.06 38.01 -46.06 34.48 -46.08 30.96 C-46.1 27.03 -46.08 23.1 -46.06 19.17 C-46.07 18.01 -46.08 16.86 -46.09 15.67 C-46.02 7.84 -44.87 2.47 -39.63 -3.62 C-25.4 -15.56 -14.15 -8.02 0 0 Z " fill="#E0E2E3" transform="translate(233.5625,142.375)"/>
<path d="M0 0 C3.4 1.96 6.09 4.3 8.62 7.31 C8.62 7.97 8.62 8.63 8.62 9.31 C9.66 9.31 9.66 9.31 10.72 9.31 C51.72 9.19 92.72 9.11 133.73 9.06 C138.65 9.05 143.57 9.04 148.49 9.04 C149.47 9.04 150.45 9.04 151.46 9.03 C167.33 9.01 183.2 8.97 199.07 8.93 C215.35 8.88 231.63 8.85 247.91 8.84 C257.96 8.84 268.01 8.81 278.06 8.77 C284.95 8.75 291.83 8.74 298.72 8.74 C302.7 8.75 306.67 8.74 310.65 8.72 C314.29 8.69 317.93 8.69 321.57 8.71 C323.51 8.71 325.45 8.69 327.39 8.67 C336.8 8.75 336.8 8.75 339.89 11.71 C342.47 15.57 343.05 18.71 342.62 23.31 C340.61 27.42 338.87 29.66 334.62 31.31 C332.04 31.56 332.04 31.56 329.06 31.56 C327.92 31.56 326.77 31.57 325.6 31.57 C323.72 31.57 323.72 31.57 321.8 31.56 C320.46 31.56 319.11 31.56 317.77 31.56 C314.07 31.57 310.37 31.56 306.67 31.56 C302.69 31.55 298.7 31.55 294.71 31.56 C287.8 31.56 280.89 31.56 273.98 31.56 C263.06 31.55 252.14 31.56 241.22 31.57 C237.49 31.58 233.75 31.58 230.02 31.59 C229.09 31.59 228.15 31.59 227.19 31.59 C216.57 31.6 205.94 31.61 195.32 31.62 C194.34 31.62 193.37 31.62 192.37 31.62 C176.62 31.63 160.87 31.62 145.13 31.62 C128.96 31.61 112.8 31.62 96.64 31.65 C86.66 31.67 76.69 31.67 66.72 31.65 C59.89 31.64 53.05 31.65 46.22 31.67 C42.28 31.68 38.34 31.68 34.39 31.67 C30.78 31.65 27.17 31.66 23.56 31.68 C21.64 31.69 19.71 31.67 17.78 31.65 C11.12 31.61 11.12 31.61 5.45 34.74 C4.55 36.01 4.55 36.01 3.62 37.31 C-2.4 42.28 -8.84 41.95 -16.38 41.31 C-20.65 40.08 -23.59 38.71 -25.81 34.76 C-29.8 30.95 -33.78 31.64 -39.11 31.72 C-40.2 31.71 -41.29 31.71 -42.42 31.7 C-44.79 31.7 -47.17 31.7 -49.54 31.72 C-53.3 31.75 -57.06 31.74 -60.81 31.73 C-68.11 31.71 -75.41 31.72 -82.72 31.74 C-92.63 31.77 -102.55 31.78 -112.46 31.75 C-116.17 31.75 -119.87 31.77 -123.57 31.79 C-125.84 31.79 -128.11 31.78 -130.38 31.78 C-131.93 31.8 -131.93 31.8 -133.5 31.82 C-139.44 31.78 -142.84 31.27 -147.38 27.31 C-149.38 24.44 -149.64 22.33 -149.31 18.88 C-149.26 18.13 -149.21 17.39 -149.15 16.63 C-147.94 13.03 -146.41 11.63 -143.38 9.31 C-139.72 8.58 -136.12 8.67 -132.41 8.73 C-131.3 8.73 -130.19 8.73 -129.05 8.72 C-126.65 8.72 -124.26 8.73 -121.86 8.75 C-118.06 8.78 -114.27 8.78 -110.47 8.77 C-102.42 8.76 -94.37 8.78 -86.31 8.81 C-76.98 8.85 -67.64 8.87 -58.31 8.84 C-54.57 8.84 -50.82 8.87 -47.08 8.9 C-44.79 8.9 -42.5 8.9 -40.21 8.89 C-39.17 8.91 -38.13 8.93 -37.05 8.94 C-31.35 9.02 -31.35 9.02 -26.83 5.88 C-26.35 5.03 -25.87 4.18 -25.38 3.31 C-17.73 -2.2 -8.74 -4.01 0 0 Z " fill="#D8DCDD" transform="translate(159.375,406.6875)"/>
<path d="M0 0 C5.56 3.26 9.11 7 11.12 13.14 C12.87 20.64 11.55 26.74 7.62 33.31 C3.68 38.08 -0.11 40.96 -6.37 41.63 C-13.06 41.86 -19 41.66 -24.38 37.31 C-24.85 36.47 -25.32 35.63 -25.81 34.76 C-29.8 30.95 -33.78 31.64 -39.11 31.72 C-40.2 31.71 -41.29 31.71 -42.42 31.7 C-44.79 31.7 -47.17 31.7 -49.54 31.72 C-53.3 31.75 -57.06 31.74 -60.81 31.73 C-68.11 31.71 -75.41 31.72 -82.72 31.74 C-92.63 31.77 -102.55 31.78 -112.46 31.75 C-116.17 31.75 -119.87 31.77 -123.57 31.79 C-125.84 31.79 -128.11 31.78 -130.38 31.78 C-131.93 31.8 -131.93 31.8 -133.5 31.82 C-139.44 31.78 -142.84 31.27 -147.38 27.31 C-149.38 24.44 -149.64 22.33 -149.31 18.88 C-149.26 18.13 -149.21 17.39 -149.15 16.63 C-147.94 13.03 -146.41 11.63 -143.38 9.31 C-139.72 8.58 -136.12 8.67 -132.41 8.73 C-131.3 8.73 -130.19 8.73 -129.05 8.72 C-126.65 8.72 -124.26 8.73 -121.86 8.75 C-118.06 8.78 -114.27 8.78 -110.47 8.77 C-102.42 8.76 -94.37 8.78 -86.31 8.81 C-76.98 8.85 -67.64 8.87 -58.31 8.84 C-54.57 8.84 -50.82 8.87 -47.08 8.9 C-44.79 8.9 -42.5 8.9 -40.21 8.89 C-39.17 8.91 -38.13 8.93 -37.05 8.94 C-31.35 9.02 -31.35 9.02 -26.83 5.88 C-26.35 5.03 -25.87 4.18 -25.38 3.31 C-17.73 -2.2 -8.74 -4.01 0 0 Z " fill="#CC3D44" transform="translate(159.375,406.6875)"/>
<path d="M0 0 C5.56 3.26 9.11 7 11.12 13.14 C12.87 20.64 11.55 26.74 7.62 33.31 C3.68 38.08 -0.11 40.96 -6.37 41.63 C-14.21 41.9 -20.03 41.2 -25.97 35.89 C-28.23 33.46 -29.33 31.75 -29.69 28.44 C-29.58 27.74 -29.48 27.04 -29.38 26.31 C-30.03 25.98 -30.69 25.65 -31.38 25.31 C-31.63 18.32 -31.28 11.81 -27.88 5.56 C-20.01 -1.52 -9.97 -4.57 0 0 Z " fill="#D43740" transform="translate(159.375,406.6875)"/>
<path d="M0 0 C15.51 0 31.02 0 47 0 C46.67 7.26 46.34 14.52 46 22 C30.82 22 15.64 22 0 22 C0.99 18.54 0.99 18.54 2 15 C2.31 12.62 2.31 12.62 2.25 10.44 C2.26 9.73 2.26 9.03 2.27 8.31 C2 5.32 2 5.32 0 0 Z " fill="#AFB4B5" transform="translate(168,416)"/>
</svg>"""

    private const val IMAGE_SVG = """<svg version="1.1" xmlns="http://www.w3.org/2000/svg" width="512" height="512">
<path d="M0 0 C1.64 -0.01 1.64 -0.01 3.32 -0.02 C6.99 -0.03 10.66 -0.02 14.33 -0.01 C16.96 -0.02 19.6 -0.02 22.23 -0.03 C28.63 -0.05 35.04 -0.04 41.44 -0.03 C46.65 -0.02 51.86 -0.02 57.06 -0.03 C57.81 -0.03 58.55 -0.03 59.31 -0.03 C60.82 -0.03 62.32 -0.03 63.83 -0.03 C77.95 -0.04 92.07 -0.03 106.18 -0.01 C118.3 0.01 130.41 0 142.52 -0.01 C156.59 -0.03 170.66 -0.04 184.72 -0.03 C186.22 -0.03 187.72 -0.03 189.22 -0.03 C190.33 -0.03 190.33 -0.03 191.46 -0.03 C196.66 -0.02 201.86 -0.03 207.06 -0.04 C214.06 -0.05 221.06 -0.04 228.06 -0.02 C230.63 -0.01 233.2 -0.01 235.77 -0.02 C239.28 -0.03 242.78 -0.02 246.29 0 C247.31 -0.01 248.33 -0.02 249.38 -0.03 C255.12 0.04 258.72 0.48 263.14 4.51 C267.13 9.14 267.72 13.02 267.65 18.93 C267.66 20.18 267.66 20.18 267.67 21.46 C267.69 24.25 267.67 27.03 267.66 29.82 C267.67 31.82 267.67 33.82 267.68 35.82 C267.7 41.25 267.69 46.68 267.68 52.11 C267.67 57.79 267.68 63.48 267.69 69.16 C267.69 78.7 267.69 88.25 267.67 97.79 C267.64 108.82 267.65 119.85 267.67 130.89 C267.69 140.36 267.69 149.83 267.68 159.3 C267.68 164.96 267.68 170.62 267.69 176.28 C267.7 181.6 267.69 186.91 267.67 192.23 C267.66 194.19 267.67 196.14 267.68 198.09 C267.69 200.75 267.67 203.42 267.65 206.08 C267.67 207.24 267.67 207.24 267.68 208.42 C267.6 213.62 266.53 216.58 263.14 220.51 C257.9 225.29 253.03 225.07 246.29 225.02 C244.65 225.03 244.65 225.03 242.97 225.03 C239.3 225.05 235.63 225.04 231.96 225.03 C229.33 225.03 226.69 225.04 224.06 225.05 C217.66 225.06 211.25 225.06 204.84 225.05 C199.64 225.04 194.43 225.04 189.22 225.04 C188.48 225.04 187.74 225.04 186.98 225.05 C185.47 225.05 183.97 225.05 182.46 225.05 C168.34 225.06 154.22 225.05 140.11 225.03 C127.99 225.01 115.88 225.01 103.77 225.03 C89.7 225.05 75.63 225.06 61.57 225.05 C60.07 225.05 58.57 225.04 57.06 225.04 C55.96 225.04 55.96 225.04 54.83 225.04 C49.63 225.04 44.43 225.04 39.23 225.05 C32.23 225.07 25.23 225.06 18.23 225.03 C15.66 225.03 13.09 225.03 10.52 225.04 C7.01 225.05 3.51 225.04 0 225.02 C-1.02 225.02 -2.04 225.03 -3.09 225.04 C-8.84 224.98 -12.43 224.54 -16.86 220.51 C-20.84 215.88 -21.43 212 -21.36 206.08 C-21.37 205.25 -21.38 204.41 -21.38 203.56 C-21.4 200.77 -21.39 197.98 -21.37 195.2 C-21.38 193.19 -21.39 191.19 -21.39 189.19 C-21.41 183.76 -21.41 178.33 -21.39 172.9 C-21.38 167.22 -21.39 161.54 -21.4 155.86 C-21.41 146.31 -21.4 136.77 -21.38 127.23 C-21.36 116.19 -21.36 105.16 -21.38 94.13 C-21.4 84.66 -21.4 75.18 -21.39 65.71 C-21.39 60.05 -21.39 54.4 -21.4 48.74 C-21.41 43.42 -21.4 38.1 -21.38 32.78 C-21.38 30.83 -21.38 28.88 -21.39 26.93 C-21.4 24.26 -21.38 21.6 -21.36 18.93 C-21.37 18.16 -21.38 17.39 -21.39 16.59 C-21.32 11.4 -20.24 8.44 -16.86 4.51 C-11.61 -0.27 -6.74 -0.06 0 0 Z " fill="#4FC3F6" transform="translate(132.85546875,143.4920654296875)"/>
<path d="M0 0 C4.32 2.26 6.87 5.08 9.74 8.95 C10.24 9.6 10.74 10.25 11.26 10.92 C12.9 13.07 14.51 15.22 16.11 17.39 C16.67 18.14 17.22 18.89 17.79 19.66 C20.13 22.81 22.45 25.97 24.76 29.13 C29.32 35.36 34.03 41.47 38.79 47.55 C43.14 53.11 47.39 58.73 51.61 64.39 C57.08 71.72 62.64 78.97 68.27 86.17 C72.1 91.08 75.87 96.03 79.59 101.02 C82.3 104.65 85.04 108.25 87.78 111.85 C89.17 113.66 90.55 115.48 91.93 117.3 C95.27 121.7 98.63 126.06 102.11 130.35 C102.72 131.12 103.33 131.89 103.97 132.68 C105.12 134.12 106.29 135.55 107.48 136.97 C108.89 138.74 110.13 140.36 111.11 142.39 C110.3 146.95 107.82 148.8 104.11 151.39 C101.75 152.57 100.33 152.52 97.7 152.52 C96.76 152.53 95.83 152.53 94.87 152.54 C93.84 152.54 92.8 152.54 91.74 152.54 C90.1 152.54 90.1 152.54 88.43 152.55 C84.75 152.56 81.06 152.56 77.38 152.57 C74.75 152.57 72.12 152.58 69.49 152.59 C61.57 152.61 53.66 152.62 45.75 152.63 C42.02 152.63 38.3 152.64 34.57 152.64 C22.19 152.66 9.81 152.67 -2.57 152.68 C-5.78 152.68 -9 152.68 -12.21 152.69 C-13.01 152.69 -13.81 152.69 -14.63 152.69 C-27.57 152.7 -40.51 152.72 -53.46 152.75 C-66.74 152.79 -80.02 152.8 -93.3 152.81 C-100.76 152.81 -108.22 152.82 -115.68 152.84 C-122.03 152.87 -128.39 152.87 -134.74 152.86 C-137.98 152.86 -141.22 152.86 -144.46 152.88 C-147.98 152.9 -151.49 152.89 -155 152.88 C-156.03 152.89 -157.05 152.9 -158.11 152.91 C-164.02 152.86 -167.36 152.29 -171.89 148.39 C-173.53 146.48 -174.59 144.58 -175.89 142.39 C-171.94 136.01 -167.4 130.21 -162.69 124.39 C-159.03 119.86 -155.49 115.25 -152.01 110.58 C-147.65 104.71 -143.2 98.91 -138.7 93.14 C-134.14 87.31 -129.65 81.44 -125.22 75.51 C-121.77 70.9 -118.28 66.33 -114.77 61.77 C-112.17 58.38 -109.58 54.99 -107.04 51.55 C-106.38 50.67 -106.38 50.67 -105.71 49.77 C-104.53 48.19 -103.37 46.61 -102.2 45.03 C-98.9 41.26 -95.89 38.75 -90.89 37.86 C-89.9 37.87 -88.91 37.88 -87.89 37.89 C-86.4 37.88 -86.4 37.88 -84.89 37.86 C-77.11 39.24 -73.24 45.38 -68.82 51.33 C-65.08 56.32 -61.45 61.12 -56.89 65.39 C-54.82 62.01 -52.76 58.63 -50.71 55.25 C-49.95 54.01 -49.2 52.77 -48.44 51.53 C-43.81 43.94 -39.24 36.31 -34.77 28.62 C-33.93 27.17 -33.08 25.72 -32.24 24.27 C-31.01 22.16 -29.78 20.05 -28.57 17.92 C-14.4 -6.86 -14.4 -6.86 0 0 Z " fill="#4CAF50" transform="translate(287.88671875,215.609375)"/>
<path d="M0 0 C1.49 -0.02 1.49 -0.02 3 -0.03 C10.47 1.29 14.29 6.97 18.56 12.69 C19.17 13.49 19.78 14.29 20.41 15.11 C21.67 16.76 22.92 18.42 24.18 20.08 C25.85 22.31 27.54 24.52 29.24 26.73 C35.94 35.46 42.6 44.23 49.27 52.98 C52.83 57.66 56.41 62.34 60 67 C64.98 73.47 69.93 79.96 74.87 86.46 C78.19 90.83 81.52 95.19 84.88 99.52 C85.78 100.69 85.78 100.69 86.7 101.88 C87.83 103.34 88.97 104.8 90.1 106.26 C90.6 106.91 91.1 107.56 91.62 108.23 C92.28 109.07 92.28 109.07 92.95 109.93 C94 111.5 94 111.5 95 114.5 C72.55 114.59 50.09 114.66 27.64 114.71 C17.22 114.73 6.79 114.76 -3.63 114.8 C-12.72 114.84 -21.81 114.87 -30.89 114.88 C-35.7 114.88 -40.52 114.89 -45.33 114.92 C-49.86 114.95 -54.38 114.96 -58.91 114.95 C-60.58 114.95 -62.24 114.96 -63.9 114.98 C-66.17 115 -68.44 114.99 -70.71 114.98 C-72.62 114.99 -72.62 114.99 -74.56 114.99 C-79.21 114.33 -82.4 112.36 -85.46 108.8 C-86.31 107.36 -87.15 105.93 -88 104.5 C-84.06 98.12 -79.52 92.32 -74.8 86.5 C-71.14 81.96 -67.6 77.36 -64.12 72.69 C-59.76 66.82 -55.32 61.01 -50.81 55.25 C-46.26 49.42 -41.76 43.55 -37.34 37.62 C-33.88 33.01 -30.39 28.44 -26.88 23.88 C-24.28 20.49 -21.69 17.1 -19.16 13.66 C-18.5 12.78 -18.5 12.78 -17.82 11.88 C-16.65 10.3 -15.48 8.72 -14.32 7.14 C-10.11 2.35 -6.43 -0.07 0 0 Z " fill="#80C684" transform="translate(200,253.5)"/>
<path d="M0 0 C0.66 -0.01 1.32 -0.02 2 -0.03 C4.92 0.74 6.09 2.19 8 4.5 C8.5 7.07 8.5 7.07 8.51 10.03 C8.52 11.15 8.53 12.27 8.54 13.43 C8.53 14.66 8.53 15.89 8.52 17.16 C8.52 18.45 8.53 19.75 8.54 21.09 C8.56 24.65 8.55 28.21 8.54 31.77 C8.53 35.51 8.54 39.24 8.54 42.98 C8.55 49.26 8.54 55.54 8.52 61.82 C8.5 69.06 8.51 76.29 8.53 83.53 C8.55 89.76 8.55 95.99 8.54 102.21 C8.53 105.93 8.53 109.64 8.55 113.35 C8.56 117.5 8.54 121.66 8.52 125.81 C8.53 127.02 8.53 128.23 8.54 129.48 C8.41 143.4 3.36 157.45 -6.19 167.82 C-19.3 180 -33.01 184.83 -50.55 184.73 C-51.92 184.73 -53.3 184.73 -54.67 184.73 C-58.37 184.73 -62.07 184.72 -65.78 184.7 C-69.65 184.69 -73.53 184.69 -77.41 184.69 C-84.74 184.68 -92.08 184.66 -99.41 184.64 C-107.77 184.62 -116.12 184.61 -124.47 184.6 C-141.65 184.58 -158.82 184.54 -176 184.5 C-175.97 186.1 -175.97 186.1 -175.94 187.74 C-175.86 191.71 -175.82 195.69 -175.78 199.66 C-175.76 201.38 -175.73 203.1 -175.7 204.82 C-175.65 207.29 -175.63 209.76 -175.61 212.23 C-175.59 213 -175.57 213.77 -175.55 214.56 C-175.55 218.06 -175.71 220.1 -177.8 222.97 C-180.42 224.8 -182.55 225.31 -185.75 225.13 C-190.7 223.75 -194.25 219.85 -198 216.5 C-199.59 215.15 -201.18 213.8 -202.78 212.46 C-204.45 211.05 -206.11 209.64 -207.78 208.22 C-210.66 205.78 -213.57 203.38 -216.5 201 C-220.65 197.61 -224.78 194.2 -228.88 190.75 C-229.41 190.3 -229.95 189.85 -230.5 189.39 C-239.62 181.69 -239.62 181.69 -240.66 178.44 C-240.59 174.8 -240.4 173.02 -238.12 170.06 C-237.09 169.2 -236.05 168.34 -235 167.5 C-233.46 166.09 -231.91 164.67 -230.38 163.25 C-225.99 159.32 -221.49 155.58 -216.91 151.88 C-214.33 149.77 -211.79 147.63 -209.26 145.46 C-207.08 143.59 -204.88 141.75 -202.68 139.91 C-201.54 138.96 -200.41 138 -199.29 137.04 C-197.6 135.61 -195.9 134.21 -194.19 132.81 C-193.19 131.98 -192.19 131.15 -191.17 130.3 C-186.92 127.88 -184.84 127.85 -180 128.5 C-177.78 130.02 -177.78 130.02 -176 132.5 C-175.65 135.39 -175.53 137.89 -175.61 140.77 C-175.62 141.56 -175.62 142.35 -175.63 143.17 C-175.65 145.7 -175.7 148.22 -175.75 150.75 C-175.77 152.47 -175.79 154.18 -175.8 155.9 C-175.85 160.1 -175.92 164.3 -176 168.5 C-159.52 168.42 -143.04 168.32 -126.55 168.21 C-118.9 168.16 -111.25 168.12 -103.59 168.08 C-96.92 168.05 -90.24 168.01 -83.57 167.96 C-80.03 167.93 -76.5 167.91 -72.97 167.9 C-69.02 167.88 -65.07 167.85 -61.11 167.82 C-59.96 167.82 -58.8 167.81 -57.62 167.81 C-43.2 167.67 -30.69 165.62 -19.82 155.52 C-9.57 144.15 -8.85 130.82 -8.86 116.27 C-8.85 115.07 -8.85 113.86 -8.85 112.62 C-8.84 110.02 -8.83 107.42 -8.83 104.81 C-8.83 100.69 -8.81 96.58 -8.79 92.46 C-8.78 91.04 -8.78 89.63 -8.77 88.22 C-8.77 87.51 -8.76 86.8 -8.76 86.07 C-8.72 76.5 -8.69 66.92 -8.67 57.34 C-8.67 50.86 -8.64 44.39 -8.6 37.91 C-8.58 34.49 -8.57 31.07 -8.58 27.64 C-8.58 23.83 -8.56 20.02 -8.53 16.21 C-8.54 15.08 -8.54 13.94 -8.55 12.78 C-8.53 11.22 -8.53 11.22 -8.51 9.63 C-8.51 8.28 -8.51 8.28 -8.51 6.91 C-7.56 2.42 -4.54 -0.07 0 0 Z " fill="#5F7C8B" transform="translate(456,255.5)"/>
<path d="M0 0 C4.95 1.38 8.51 5.27 12.25 8.63 C13.84 9.98 15.43 11.33 17.03 12.67 C18.7 14.08 20.37 15.49 22.03 16.91 C24.91 19.35 27.83 21.75 30.75 24.13 C34.91 27.51 39.03 30.93 43.13 34.38 C43.66 34.83 44.2 35.27 44.75 35.74 C53.87 43.44 53.87 43.44 54.92 46.69 C54.84 50.33 54.65 52.11 52.38 55.07 C51.35 55.93 50.3 56.78 49.25 57.63 C47.71 59.04 46.17 60.46 44.63 61.88 C40.24 65.8 35.74 69.55 31.16 73.25 C28.58 75.36 26.04 77.5 23.51 79.67 C21.33 81.54 19.13 83.38 16.93 85.22 C15.8 86.17 14.67 87.13 13.54 88.09 C11.86 89.52 10.15 90.92 8.44 92.32 C7.44 93.15 6.45 93.97 5.42 94.83 C1.17 97.24 -0.91 97.28 -5.75 96.63 C-7.96 95.11 -7.96 95.11 -9.75 92.63 C-10.1 89.74 -10.21 87.24 -10.14 84.36 C-10.13 83.57 -10.13 82.78 -10.12 81.96 C-10.1 79.43 -10.05 76.91 -10 74.38 C-9.98 72.66 -9.96 70.95 -9.94 69.23 C-9.9 65.03 -9.83 60.83 -9.75 56.63 C-26.23 56.71 -42.71 56.81 -59.19 56.92 C-66.85 56.97 -74.5 57.01 -82.15 57.05 C-88.83 57.08 -95.51 57.12 -102.18 57.17 C-105.71 57.2 -109.24 57.22 -112.77 57.23 C-116.73 57.25 -120.68 57.28 -124.63 57.31 C-126.36 57.31 -126.36 57.31 -128.13 57.32 C-142.54 57.46 -155.05 59.51 -165.93 69.61 C-176.17 80.98 -176.89 94.3 -176.89 108.86 C-176.89 110.06 -176.89 111.26 -176.9 112.51 C-176.91 115.11 -176.91 117.71 -176.91 120.32 C-176.92 124.44 -176.94 128.55 -176.96 132.67 C-176.96 134.09 -176.97 135.5 -176.98 136.91 C-176.98 137.62 -176.98 138.33 -176.98 139.06 C-177.03 148.63 -177.06 158.21 -177.07 167.79 C-177.08 174.26 -177.1 180.74 -177.14 187.22 C-177.16 190.64 -177.17 194.06 -177.17 197.49 C-177.17 201.3 -177.19 205.11 -177.22 208.92 C-177.21 210.05 -177.2 211.18 -177.2 212.35 C-177.21 213.39 -177.22 214.43 -177.23 215.49 C-177.24 216.39 -177.24 217.29 -177.24 218.22 C-177.91 221.39 -179.27 222.61 -181.75 224.63 C-183.75 225.16 -183.75 225.16 -185.75 225.13 C-186.41 225.14 -187.07 225.15 -187.75 225.16 C-190.67 224.38 -191.84 222.94 -193.75 220.63 C-194.25 218.06 -194.25 218.06 -194.25 215.1 C-194.27 213.98 -194.28 212.86 -194.29 211.7 C-194.28 210.47 -194.27 209.24 -194.26 207.97 C-194.27 206.67 -194.28 205.38 -194.28 204.04 C-194.3 200.48 -194.3 196.92 -194.28 193.36 C-194.27 189.62 -194.28 185.89 -194.29 182.15 C-194.3 175.87 -194.29 169.59 -194.27 163.31 C-194.25 156.07 -194.25 148.83 -194.27 141.6 C-194.29 135.37 -194.3 129.14 -194.29 122.91 C-194.28 119.2 -194.28 115.49 -194.29 111.78 C-194.31 107.62 -194.29 103.47 -194.26 99.32 C-194.27 98.11 -194.28 96.89 -194.29 95.64 C-194.15 81.73 -189.1 67.68 -179.56 57.31 C-166.44 45.13 -152.74 40.3 -135.19 40.4 C-133.82 40.4 -132.45 40.4 -131.08 40.4 C-127.38 40.4 -123.67 40.41 -119.97 40.43 C-116.09 40.44 -112.21 40.44 -108.34 40.44 C-101 40.45 -93.67 40.46 -86.33 40.48 C-77.98 40.51 -69.63 40.52 -61.27 40.53 C-44.1 40.55 -26.92 40.58 -9.75 40.63 C-9.77 39.56 -9.79 38.49 -9.81 37.39 C-9.88 33.41 -9.93 29.44 -9.97 25.47 C-9.99 23.75 -10.01 22.03 -10.05 20.31 C-10.1 17.84 -10.12 15.37 -10.14 12.89 C-10.16 12.13 -10.18 11.36 -10.2 10.57 C-10.2 7.07 -10.04 5.03 -7.95 2.16 C-5.32 0.33 -3.2 -0.18 0 0 Z " fill="#5F7C8B" transform="translate(241.74609375,31.37109375)"/>
<path d="M0 0 C2.21 1.5 2.21 1.5 4 4 C4.39 6.95 4.55 9.52 4.52 12.47 C4.52 13.32 4.53 14.17 4.54 15.05 C4.56 17.87 4.54 20.68 4.53 23.5 C4.53 25.46 4.54 27.42 4.54 29.37 C4.55 33.48 4.54 37.58 4.52 41.68 C4.5 46.94 4.51 52.2 4.54 57.46 C4.55 61.5 4.55 65.54 4.54 69.58 C4.53 71.52 4.54 73.46 4.55 75.4 C4.56 78.11 4.54 80.82 4.52 83.53 C4.53 84.33 4.53 85.14 4.54 85.96 C4.49 89.49 4.3 91.59 2.19 94.48 C-0.43 96.3 -2.55 96.81 -5.75 96.63 C-10.7 95.25 -14.25 91.35 -18 88 C-19.59 86.65 -21.18 85.3 -22.78 83.96 C-24.45 82.55 -26.11 81.14 -27.78 79.72 C-30.66 77.28 -33.57 74.88 -36.5 72.5 C-40.65 69.11 -44.78 65.7 -48.88 62.25 C-49.41 61.8 -49.95 61.35 -50.5 60.89 C-59.62 53.19 -59.62 53.19 -60.66 49.94 C-60.59 46.3 -60.4 44.52 -58.12 41.56 C-57.09 40.7 -56.05 39.84 -55 39 C-53.46 37.59 -51.91 36.17 -50.38 34.75 C-45.99 30.82 -41.49 27.08 -36.91 23.38 C-34.33 21.27 -31.79 19.13 -29.26 16.96 C-27.08 15.09 -24.88 13.25 -22.68 11.41 C-21.54 10.46 -20.41 9.5 -19.29 8.54 C-17.6 7.11 -15.9 5.71 -14.19 4.31 C-13.19 3.48 -12.19 2.65 -11.17 1.8 C-6.92 -0.62 -4.84 -0.65 0 0 Z " fill="#445963" transform="translate(276,384)"/>
<path d="M0 0 C4.95 1.38 8.51 5.27 12.25 8.63 C13.84 9.98 15.43 11.33 17.03 12.67 C18.7 14.08 20.37 15.49 22.03 16.91 C24.91 19.35 27.83 21.75 30.75 24.13 C34.91 27.51 39.03 30.93 43.13 34.38 C43.66 34.83 44.2 35.27 44.75 35.74 C53.87 43.44 53.87 43.44 54.92 46.69 C54.84 50.33 54.65 52.11 52.38 55.07 C51.35 55.93 50.3 56.78 49.25 57.63 C47.71 59.04 46.17 60.46 44.63 61.88 C40.24 65.8 35.74 69.55 31.16 73.25 C28.58 75.36 26.04 77.5 23.51 79.67 C21.33 81.54 19.13 83.38 16.93 85.22 C15.8 86.17 14.67 87.13 13.54 88.09 C11.86 89.52 10.15 90.92 8.44 92.32 C7.44 93.15 6.45 93.97 5.42 94.83 C1.17 97.24 -0.91 97.28 -5.75 96.63 C-7.96 95.13 -7.96 95.13 -9.75 92.63 C-10.14 89.68 -10.29 87.11 -10.26 84.16 C-10.27 83.31 -10.28 82.46 -10.28 81.58 C-10.3 78.76 -10.29 75.95 -10.28 73.13 C-10.28 71.17 -10.28 69.21 -10.29 67.26 C-10.29 63.15 -10.29 59.05 -10.27 54.95 C-10.24 49.69 -10.26 44.43 -10.28 39.17 C-10.3 35.13 -10.29 31.09 -10.28 27.04 C-10.28 25.11 -10.28 23.17 -10.29 21.23 C-10.3 18.52 -10.29 15.81 -10.26 13.1 C-10.27 12.3 -10.28 11.49 -10.29 10.67 C-10.23 7.14 -10.04 5.04 -7.94 2.15 C-5.32 0.33 -3.19 -0.18 0 0 Z " fill="#455A64" transform="translate(241.74609375,31.37109375)"/>
<path d="M0 0 C5.82 3.57 10.32 8.95 12.4 15.47 C13.34 22.95 12.22 29.39 7.92 35.6 C3.35 41.26 -1.17 44.89 -8.5 45.84 C-15.03 46.11 -20.34 45.52 -25.6 41.47 C-31.86 35.65 -35.66 29.73 -36.04 21.03 C-35.8 14.36 -34.11 10.1 -29.51 5.27 C-20.5 -3.09 -11.49 -5.07 0 0 Z " fill="#FCF077" transform="translate(211.6015625,178.53125)"/>
</svg>"""
}

val FALLBACK_AUDIO_SVG get() = FallbackIcons.AUDIO
val FALLBACK_VIDEO_SVG get() = FallbackIcons.VIDEO
val FALLBACK_IMAGE_SVG get() = FallbackIcons.IMAGE