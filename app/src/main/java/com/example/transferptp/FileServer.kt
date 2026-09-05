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
                                    .path { font-family: monospace; background: #eee; padding: 8px; border-radius: 4px; display: block; margin: 15px 0; word-break: break-all; }
                                    ul { list-style: none; padding: 0; }
                                    li { display: flex; align-items: center; padding: 12px; border-bottom: 1px solid #eee; }
                                    .thumb-container { width: 60px; height: 60px; margin-right: 15px; display: flex; align-items: center; justify-content: center; background: #f0f0f0; border-radius: 8px; overflow: hidden; flex-shrink: 0; }
                                    .thumb-container img { width: 100%; height: 100%; object-fit: cover; }
                                    .icon { font-size: 1.5rem; }
                                    .file-info { flex-grow: 1; min-width: 0; }
                                    .file-name { font-weight: 500; color: #007bff; text-decoration: none; word-break: break-all; }
                                    .file-meta { font-size: 0.8rem; color: #6c757d; margin-top: 4px; }
                                    .btn-back { display: inline-block; margin-bottom: 15px; text-decoration: none; color: #333; font-weight: bold; }
                                """.trimIndent()
                            }
                        }
                        body {
                            div(classes = "container") {
                                h1 { +"File Transfer" }
                                span(classes = "path") { +"Path: ${currentDir.absolutePath}" }

                                val relativeCurrent = currentDir.absolutePath.removePrefix(root.absolutePath).removePrefix("/")
                                if (relativeCurrent.isNotEmpty()) {
                                    val parentPath = currentDir.parentFile?.absolutePath?.removePrefix(root.absolutePath) ?: ""
                                    a(href = "/?path=$parentPath", classes = "btn-back") { +"← Back to parent" }
                                }

                                ul {
                                    val files = currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                                    if (files == null) {
                                        li { +"⚠️ Permission Denied: Please check storage settings." }
                                    } else {
                                        files.forEach { file ->
                                            val rel = file.absolutePath.removePrefix(root.absolutePath).removePrefix("/")
                                            val ext = file.extension.lowercase()
                                            val isImg = ext in listOf("jpg", "jpeg", "png", "gif", "webp")
                                            val isVid = ext in listOf("mp4", "mkv", "mov", "avi")

                                            li {
                                                div(classes = "thumb-container") {
                                                    if (isImg || isVid) {
                                                        img(src = "/thumbnail?path=$rel")
                                                    } else {
                                                        span(classes = "icon") {
                                                            +if (file.isDirectory) "📁" else "📄"
                                                        }
                                                    }
                                                }
                                                div(classes = "file-info") {
                                                    if (file.isDirectory) {
                                                        a(href = "/?path=$rel", classes = "file-name") { +file.name }
                                                        div(classes = "file-meta") { +"Folder" }
                                                    } else {
                                                        a(href = "/download?path=$rel", classes = "file-name") { +file.name }
                                                        div(classes = "file-meta") {
                                                            +"${file.length() / 1024} KB • ${file.extension.uppercase()}"
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
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
                        val options = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeFile(file.absolutePath, options)
                        options.inSampleSize = calculateInSampleSize(options, 120, 120)
                        options.inJustDecodeBounds = false
                        BitmapFactory.decodeFile(file.absolutePath, options)
                    } else if (ext in listOf("mp4", "mkv", "mov", "avi")) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        } catch (e: Exception) {
                            null
                        } finally {
                            retriever.release()
                        }
                    } else null

                    if (bitmap != null) {
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                        call.respondBytes(stream.toByteArray(), ContentType.Image.JPEG)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
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
