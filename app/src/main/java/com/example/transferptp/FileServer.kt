package com.example.transferptp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Environment
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface

class FileServer(private val context: Context) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    fun start(port: Int = 8080, onStarted: (String) -> Unit) {
        server = embeddedServer(Netty, port = port) {
            install(PartialContent)
            routing {
                get("/") {
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
                                    
                                    /* Preview Modal */
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
                                                a(href = "/?path=$cumulativePath") { +part }
                                            }
                                        }
                                    }
                                }

                                val relativeCurrent = currentDir.absolutePath.removePrefix(root.absolutePath).removePrefix("/")
                                if (relativeCurrent.isNotEmpty()) {
                                    val parentPath = currentDir.parentFile?.absolutePath?.removePrefix(root.absolutePath) ?: ""
                                    a(href = "/?path=$parentPath", classes = "btn-back") { 
                                        style = "display: inline-block; margin-bottom: 15px; text-decoration: none; color: #555; font-weight: bold;"
                                        +"← Back" 
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
                                                isImg -> "image"
                                                isVid -> "video"
                                                isAud -> "audio"
                                                isTxt -> "text"
                                                isPdf -> "pdf"
                                                else -> null
                                            }

                                            li {
                                                div(classes = "thumb-container") {
                                                    if (isImg || isVid || isAud) {
                                                        img(src = "/thumbnail?path=$rel") {
                                                            onError = "this.src='https://cdn-icons-png.flaticon.com/512/3844/3844724.png'"
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
                                                div(classes = "file-info") {
                                                    if (file.isDirectory) {
                                                        a(href = "/?path=$rel", classes = "file-name") { +file.name }
                                                        div(classes = "file-meta") { +"Folder" }
                                                    } else {
                                                        span(classes = "file-name") {
                                                            if (previewType != null) {
                                                                onClick = "showPreview('/stream?path=$rel', '$previewType', '${file.name}', '/thumbnail?path=$rel')"
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
                                                                onClick = "showPreview('/stream?path=$rel', '$previewType', '${file.name}', '/thumbnail?path=$rel')"
                                                                +"Preview"
                                                            }
                                                        }
                                                        a(href = "/download?path=$rel", classes = "btn btn-download") { +"Download" }
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
                }

                get("/thumbnail") {
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
                    val root = Environment.getExternalStorageDirectory()
                    val path = call.parameters["path"] ?: return@get call.respondText("Missing path")
                    val file = File(root, path.removePrefix("/"))
                    if (file.exists() && !file.isDirectory) {
                        call.respondFile(file)
                    } else {
                        call.respondText("File not found", status = HttpStatusCode.NotFound)
                    }
                }

                get("/download") {
                    val root = Environment.getExternalStorageDirectory()
                    val path = call.parameters["path"] ?: return@get call.respondText("Missing path")
                    val file = File(root, path.removePrefix("/"))
                    if (file.exists() && !file.isDirectory) {
                        call.response.header(
                            HttpHeaders.ContentDisposition,
                            ContentDisposition.Attachment.withParameter(
                                ContentDisposition.Parameters.FileName,
                                file.name
                            ).toString()
                        )
                        call.respondFile(file)
                    } else {
                        call.respondText("File not found", status = HttpStatusCode.NotFound)
                    }
                }
            }
        }.start(wait = false)
        onStarted("http://${getLocalIpAddress()}:$port")
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun stop() { server?.stop(1000, 2000) }

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
