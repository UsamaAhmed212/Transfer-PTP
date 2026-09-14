package com.example.transferptp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Environment
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
import kotlinx.html.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URLEncoder

class FileServer(private val context: Context) {
    
    private var currentPin: String = ""

    companion object {
        private var server: EmbeddedServer<*, *>? = null
        
        fun stopServer() {
            Log.d("FileServer", "Stopping global server instance")
            try {
                server?.stop(100, 500)
            } catch (e: Exception) {
                Log.e("FileServer", "Error stopping server", e)
            } finally {
                server = null
            }
        }
    }

    fun start(port: Int = 8080, onStarted: (String, String) -> Unit) {
        Log.d("FileServer", "Start method called")
        if (server != null) {
            Log.d("FileServer", "Server already running, stopping it first")
            stopServer()
        }

        currentPin = (1000..9999).random().toString()

        try {
            Log.d("FileServer", "Configuring server on port $port with PIN $currentPin")
            val newServer = embeddedServer(CIO, port = port) {
                install(Sessions) {
                    cookie<String>("SESSION_ID") {
                        cookie.path = "/"
                        cookie.maxAgeInSeconds = 3600
                    }
                }
                install(PartialContent)
                
                routing {
                    get("/") {
                        val session = call.sessions.get<String>()
                        if (session == "VERIFIED") {
                            // Show File Browser
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
                                            .breadcrumb { display: flex; align-items: center; background: #f0f2f5; padding: 10px 15px; border-radius: 8px; margin: 15px 0; font-size: 0.95rem; overflow-x: auto; white-space: nowrap; border: 1px solid #ddd; }
                                            .breadcrumb a { text-decoration: none; color: #007bff; font-weight: 500; padding: 2px 6px; border-radius: 4px; }
                                            .breadcrumb a:hover { background: #e7f1ff; text-decoration: underline; }
                                            .breadcrumb .separator { color: #888; margin: 0 8px; font-weight: bold; }
                                            .breadcrumb .current { color: #333; font-weight: bold; padding: 2px 6px; }
                                            ul { list-style: none; padding: 0; }
                                            li { display: flex; align-items: center; padding: 12px; border-bottom: 1px solid #eee; }
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
                                                    const img = document.createElement('img');
                                                    img.src = url;
                                                    img.style.maxWidth = '100%';
                                                    img.style.maxHeight = '100%';
                                                    content.appendChild(img);
                                                } else if (type === 'audio') {
                                                    const art = document.createElement('img');
                                                    art.src = thumbUrl;
                                                    art.className = 'music-art';
                                                    art.onerror = function() { this.src = 'https://cdn-icons-png.flaticon.com/512/3844/3844724.png'; };
                                                    content.appendChild(art);
                                                    
                                                    const audio = document.createElement('audio');
                                                    audio.src = url;
                                                    audio.controls = true;
                                                    audio.autoplay = true;
                                                    audio.preload = 'metadata';
                                                    content.appendChild(audio);
                                                } else if (type === 'video') {
                                                    const video = document.createElement('video');
                                                    video.src = url;
                                                    video.controls = true;
                                                    video.autoplay = true;
                                                    video.preload = 'metadata';
                                                    content.appendChild(video);
                                                } else if (type === 'text') {
                                                    try {
                                                        const response = await fetch(url);
                                                        const text = await response.text();
                                                        const pre = document.createElement('pre');
                                                        pre.className = 'text-preview';
                                                        pre.innerText = text;
                                                        content.appendChild(pre);
                                                    } catch (e) {
                                                        content.innerHTML = '<p style="color:white">Error loading text file</p>';
                                                    }
                                                } else if (type === 'pdf') {
                                                    const iframe = document.createElement('iframe');
                                                    iframe.src = url;
                                                    content.appendChild(iframe);
                                                }
                                                document.getElementById('preview-title').innerText = name;
                                            }
                                            function closePreview() {
                                                const overlay = document.getElementById('preview-overlay');
                                                document.getElementById('preview-content').innerHTML = '';
                                                overlay.style.display = 'none';
                                            }
                                        """.trimIndent()
                                    }
                                }
                                body {
                                    div(classes = "container") {
                                        h1 { +"File Transfer" }
                                        div(classes = "breadcrumb") {
                                            a(href = "/") { +"🏠 Internal Storage" }
                                            val relativePath = currentDir.absolutePath.removePrefix(root.absolutePath).removePrefix("/")
                                            if (relativePath.isNotEmpty()) {
                                                val parts = relativePath.split("/")
                                                var cumulativePath = ""
                                                parts.forEachIndexed { index, part ->
                                                    span(classes = "separator") { +"❯" }
                                                    cumulativePath += if (cumulativePath.isEmpty()) part else "/$part"
                                                    if (index == parts.size - 1) {
                                                        span(classes = "current") { +part }
                                                    } else {
                                                        a(href = "/?path=${cumulativePath.encodeURLParameter()}") { +part }
                                                    }
                                                }
                                            }
                                        }
                                        ul {
                                            val files = currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                                            if (files == null) {
                                                li { +"⚠️ Permission Denied" }
                                            } else {
                                                files.forEach { file ->
                                                    val rel = file.absolutePath.removePrefix(root.absolutePath).removePrefix("/")
                                                    val ext = file.extension.lowercase()
                                                    val isImg = ext in listOf("jpg", "jpeg", "png", "gif", "webp")
                                                    val isVid = ext in listOf("mp4", "mkv", "mov", "avi")
                                                    val isAud = ext in listOf("mp3", "wav", "m4a", "flac")
                                                    val isTxt = ext in listOf("txt", "log", "json", "xml", "kt", "java", "html", "css", "js")
                                                    val isPdf = ext == "pdf"
                                                    val previewType = when {
                                                        isImg -> "image"; isVid -> "video"; isAud -> "audio"; isTxt -> "text"; isPdf -> "pdf"; else -> null
                                                    }
                                                    li {
                                                        div(classes = "thumb-container") {
                                                            if (isImg || isVid || isAud) {
                                                                img(src = "/thumbnail?path=${rel.encodeURLParameter()}") {
                                                                    onError = "this.src='https://cdn-icons-png.flaticon.com/512/3844/3844724.png'"
                                                                }
                                                            } else {
                                                                span(classes = "icon") {
                                                                    +when { file.isDirectory -> "📁"; isTxt -> "📄"; isPdf -> "📕"; else -> "📄" }
                                                                }
                                                            }
                                                        }
                                                        div(classes = "file-info") {
                                                            if (file.isDirectory) {
                                                                a(href = "/?path=${rel.encodeURLParameter()}", classes = "file-name") { +file.name }
                                                                div(classes = "file-meta") { +"Folder" }
                                                            } else {
                                                                span(classes = "file-name") {
                                                                    if (previewType != null) {
                                                                        onClick = "showPreview('/stream?path=${rel.encodeURLParameter()}', '$previewType', '${file.name}', '/thumbnail?path=${rel.encodeURLParameter()}')"
                                                                    }
                                                                    +file.name
                                                                }
                                                                div(classes = "file-meta") {
                                                                    +"${file.length() / 1024} KB • ${file.extension.uppercase()}"
                                                                }
                                                            }
                                                        }
                                                        if (!file.isDirectory) {
                                                            div(classes = "actions") {
                                                                if (previewType != null) {
                                                                    button(classes = "btn btn-preview") {
                                                                        onClick = "showPreview('/stream?path=${rel.encodeURLParameter()}', '$previewType', '${file.name}', '/thumbnail?path=${rel.encodeURLParameter()}')"
                                                                        +"Preview"
                                                                    }
                                                                }
                                                                a(href = "/download?path=${rel.encodeURLParameter()}", classes = "btn btn-download") { +"Download" }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    div {
                                        id = "preview-overlay"
                                        span(classes = "close-btn") { onClick = "closePreview()"; +"×" }
                                        h3 { id = "preview-title"; style = "color: white; margin-bottom: 20px;" }
                                        div { id = "preview-content" }
                                    }
                                }
                            }
                        } else {
                            // Show PIN Entry
                            val html = """
                                <!DOCTYPE html>
                                <html lang="en">
                                <head>
                                   <meta charset="UTF-8">
                                   <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                   <style>
                                       * { margin: 0; padding: 0; box-sizing: border-box; }
                                       :root { --bg: #e7ebf0; --light: #ffffff; --dark: #b8c0ca; --text: #39424e; --accent: #00a8ff; --success: #16a085; --danger: #e74c3c; }
                                       body { min-height: 100vh; display: flex; justify-content: center; align-items: center; font-family: Arial, Helvetica, sans-serif; background: var(--bg); overflow: hidden; }
                                       body::before, body::after { content: ""; position: fixed; width: 280px; height: 280px; border-radius: 50%; filter: blur(80px); opacity: .25; z-index: -1; }
                                       body::before { background: #00a8ff; top: -100px; left: -80px; }
                                       body::after { background: #7c4dff; bottom: -100px; right: -80px; }
                                       .otp-wrapper { position: relative; width: 420px; max-width: calc(100% - 30px); padding: 3px; border-radius: 28px; overflow: hidden; box-shadow: 18px 18px 35px rgba(163, 174, 187, .65), -18px -18px 35px rgba(255, 255, 255, .95); }
                                       .otp-wrapper::before { content: ""; position: absolute; inset: -50%; background: conic-gradient(transparent 0deg, transparent 30deg, #00a8ff 55deg, #ffffff 65deg, #00e5ff 75deg, transparent 100deg, transparent 180deg, #7c4dff 210deg, #00a8ff 240deg, transparent 270deg, transparent 360deg); animation: electricSpin 3s linear infinite; }
                                       @keyframes electricSpin { to { transform: rotate(360deg); } }
                                       .otp-wrapper::after { content: ""; position: absolute; inset: 0; border-radius: 28px; box-shadow: 0 0 8px #00a8ff, 0 0 20px rgba(0, 168, 255, .6), 0 0 40px rgba(0, 168, 255, .25); pointer-events: none; animation: electricGlow 1.4s ease-in-out infinite alternate; }
                                       @keyframes electricGlow { from { opacity: .35; } to { opacity: 1; } }
                                       .otp-card { position: relative; z-index: 2; background: var(--bg); border-radius: 25px; padding: 42px 35px; text-align: center; }
                                       .lock { width: 75px; height: 75px; margin: 0 auto 22px; display: flex; align-items: center; justify-content: center; font-size: 32px; border-radius: 50%; background: var(--bg); box-shadow: inset 7px 7px 13px var(--dark), inset -7px -7px 13px var(--light), 8px 8px 18px rgba(163, 174, 187, .4), -8px -8px 18px rgba(255, 255, 255, .8); animation: lockPulse 2s ease-in-out infinite; }
                                       @keyframes lockPulse { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-5px); } }
                                       h1 { color: var(--text); font-size: 27px; margin-bottom: 10px; letter-spacing: .5px; text-shadow: 2px 2px 3px rgba(163, 174, 187, .8), -1px -1px 2px white; }
                                       .description { color: #77818d; font-size: 14px; line-height: 1.6; margin-bottom: 28px; }
                                       .otp-inputs { display: flex; justify-content: center; gap: 10px; margin: 25px 0; }
                                       .otp-input { width: 47px; height: 58px; border: none; outline: none; text-align: center; font-size: 22px; font-weight: bold; color: var(--text); border-radius: 13px; background: var(--bg); box-shadow: inset 5px 5px 9px var(--dark), inset -5px -5px 9px var(--light); transition: .25s ease; }
                                       .otp-input:focus { color: var(--accent); box-shadow: inset 2px 2px 5px var(--dark), inset -2px -2px 5px var(--light), 0 0 0 2px rgba(0, 168, 255, .2), 0 0 15px rgba(0, 168, 255, .35); transform: translateY(-3px); }
                                       .verify-btn { width: 100%; height: 54px; border: none; border-radius: 15px; background: var(--bg); color: var(--text); font-size: 15px; font-weight: bold; cursor: pointer; letter-spacing: 1px; box-shadow: 8px 8px 15px var(--dark), -8px -8px 15px var(--light); transition: .25s ease; }
                                       .verify-btn:hover { color: var(--accent); transform: translateY(-2px); }
                                       .verify-btn:active { transform: translateY(1px); box-shadow: inset 5px 5px 10px var(--dark), inset -5px -5px 10px var(--light); }
                                       .message { min-height: 20px; margin-top: 18px; font-size: 13px; font-weight: bold; }
                                       .message.success { color: var(--success); }
                                       .message.error { color: var(--danger); }
                                       .shake { animation: shake .45s ease; }
                                       @keyframes shake { 0%, 100% { transform: translateX(0); } 20% { transform: translateX(-8px); } 40% { transform: translateX(8px); } 60% { transform: translateX(-6px); } 80% { transform: translateX(6px); } }
                                       .success-screen { display: none; animation: successAppear .5s ease forwards; }
                                       @keyframes successAppear { from { opacity: 0; transform: scale(.8); } to { opacity: 1; transform: scale(1); } }
                                       .success-icon { width: 100px; height: 100px; margin: 0 auto 25px; border-radius: 50%; display: flex; justify-content: center; align-items: center; font-size: 50px; color: var(--success); background: var(--bg); box-shadow: inset 7px 7px 14px var(--dark), inset -7px -7px 14px var(--light), 8px 8px 18px rgba(163, 174, 187, .4), -8px -8px 18px rgba(255, 255, 255, .8); animation: successPop .7s cubic-bezier(.17, .67, .35, 1.4); }
                                       @keyframes successPop { 0% { transform: scale(0) rotate(-90deg); } 70% { transform: scale(1.15) rotate(10deg); } 100% { transform: scale(1) rotate(0); } }
                                       .success-screen h2 { color: var(--success); margin-bottom: 10px; }
                                       .success-screen p { color: #77818d; font-size: 14px; line-height: 1.6; }
                                       @media (max-width: 480px) { .otp-card { padding: 35px 20px; } .otp-inputs { gap: 7px; } .otp-input { width: 42px; height: 54px; font-size: 20px; } h1 { font-size: 24px; } }
                                   </style>
                                </head>
                                <body>
                                    <div class="otp-wrapper">
                                       <div class="otp-card">
                                           <div id="otpForm">
                                               <div class="lock">🔐</div>
                                               <h1>Verify Your OTP</h1>
                                               <p class="description">Enter 4-digit verification code</p>
                                               <div class="otp-inputs" id="otpInputs">
                                                   <input class="otp-input" type="text" inputmode="numeric" maxlength="1" autofocus>
                                                   <input class="otp-input" type="text" inputmode="numeric" maxlength="1">
                                                   <input class="otp-input" type="text" inputmode="numeric" maxlength="1">
                                                   <input class="otp-input" type="text" inputmode="numeric" maxlength="1">
                                               </div>
                                               <button class="verify-btn" id="verifyBtn">VERIFY OTP</button>
                                               <div class="message" id="message"></div>
                                           </div>
                                           <div class="success-screen" id="successScreen">
                                               <div class="success-icon">✓</div>
                                               <h2>Verification Successful</h2>
                                               <p>Your OTP has been verified successfully.</p>
                                           </div>
                                       </div>
                                   </div>
                                   <script>
                                       const inputs = document.querySelectorAll('.otp-input');
                                       const btn = document.getElementById('verifyBtn');
                                       const msg = document.getElementById('message');
                                       const form = document.getElementById('otpForm');
                                       const success = document.getElementById('successScreen');
                                       inputs.forEach((input, index) => {
                                           input.addEventListener('input', (e) => {
                                               if (e.target.value.length === 1 && index < inputs.length - 1) inputs[index + 1].focus();
                                           });
                                           input.addEventListener('keydown', (e) => {
                                               if (e.key === 'Backspace' && !e.target.value && index > 0) inputs[index - 1].focus();
                                           });
                                       });
                                       btn.addEventListener('click', async () => {
                                           const pin = Array.from(inputs).map(i => i.value).join('');
                                           if (pin.length !== 4) { msg.innerText = 'Please enter 4 digits'; msg.className = 'message error'; return; }
                                           try {
                                               const res = await fetch('/verify', {
                                                   method: 'POST',
                                                   headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                                                   body: 'pin=' + pin
                                               });
                                               const text = await res.text();
                                               if (text === 'OK') {
                                                   form.style.display = 'none';
                                                   success.style.display = 'block';
                                                   setTimeout(() => window.location.reload(), 1000);
                                               } else {
                                                   msg.innerText = 'Invalid PIN'; msg.className = 'message error';
                                                   form.classList.add('shake');
                                                   setTimeout(() => form.classList.remove('shake'), 500);
                                               }
                                           } catch (e) { msg.innerText = 'Server Error'; msg.className = 'message error'; }
                                       });
                                   </script>
                                </body>
                                </html>
                            """.trimIndent()
                            call.respondText(html, ContentType.Text.Html)
                        }
                    }

                    post("/verify") {
                        val params = call.receiveParameters()
                        val pin = params["pin"]
                        if (pin == currentPin) {
                            call.sessions.set("VERIFIED")
                            call.respondText("OK")
                        } else {
                            call.respondText("FAIL", status = HttpStatusCode.Unauthorized)
                        }
                    }

                    get("/thumbnail") {
                        val session = call.sessions.get<String>()
                        if (session != "VERIFIED") return@get call.respond(HttpStatusCode.Forbidden)

                        val root = Environment.getExternalStorageDirectory()
                        val path = call.parameters["path"] ?: return@get call.respondText("Missing path")
                        val file = File(root, path.removePrefix("/"))
                        if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound)

                        val ext = file.extension.lowercase()
                        val bitmap: Bitmap? = if (ext in listOf("jpg", "jpeg", "png", "gif", "webp")) {
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.absolutePath, options)
                            options.inSampleSize = calculateInSampleSize(options, 200, 200)
                            options.inJustDecodeBounds = false
                            BitmapFactory.decodeFile(file.absolutePath, options)
                        } else if (ext in listOf("mp4", "mkv", "mov", "avi")) {
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(file.absolutePath)
                                retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            } catch (e: Exception) { null } finally { retriever.release() }
                        } else if (ext in listOf("mp3", "wav", "m4a", "flac")) {
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(file.absolutePath)
                                val art = retriever.embeddedPicture
                                if (art != null) BitmapFactory.decodeByteArray(art, 0, art.size) else null
                            } catch (e: Exception) { null } finally { retriever.release() }
                        } else null

                        if (bitmap != null) {
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                            call.respondBytes(stream.toByteArray(), ContentType.Image.JPEG)
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    }

                    get("/stream") {
                        val session = call.sessions.get<String>()
                        if (session != "VERIFIED") return@get call.respond(HttpStatusCode.Forbidden)

                        val root = Environment.getExternalStorageDirectory()
                        val path = call.parameters["path"] ?: return@get call.respondText("Missing path")
                        val file = File(root, path.removePrefix("/"))
                        if (file.exists() && !file.isDirectory) call.respondFile(file)
                        else call.respondText("File not found", status = HttpStatusCode.NotFound)
                    }

                    get("/download") {
                        val session = call.sessions.get<String>()
                        if (session != "VERIFIED") return@get call.respond(HttpStatusCode.Forbidden)

                        val root = Environment.getExternalStorageDirectory()
                        val path = call.parameters["path"] ?: return@get call.respondText("Missing path")
                        val file = File(root, path.removePrefix("/"))
                        if (file.exists() && !file.isDirectory) {
                            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.name).toString())
                            call.respondFile(file)
                        } else call.respondText("File not found", status = HttpStatusCode.NotFound)
                    }
                }
            }
            server = newServer
            newServer.start(wait = false)
            Log.d("FileServer", "Server engine started")
            onStarted("http://${getLocalIpAddress()}:$port", currentPin)
        } catch (e: Exception) {
            Log.e("FileServer", "Error starting server", e)
            throw e
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) inSampleSize *= 2
        }
        return inSampleSize
    }

    fun stop() { stopServer() }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is InetAddress) {
                        val host = address.hostAddress
                        if (host != null && !host.contains(":")) return host
                    }
                }
            }
        } catch (ex: Exception) { ex.printStackTrace() }
        return "127.0.0.1"
    }
}

fun String.encodeURLParameter(): String = URLEncoder.encode(this, "UTF-8")
