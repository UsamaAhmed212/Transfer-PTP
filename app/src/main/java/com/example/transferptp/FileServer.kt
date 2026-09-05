package com.example.transferptp

import android.content.Context
import android.os.Environment
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
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
                                    body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; padding: 20px; background-color: #f8f9fa; color: #333; }
                                    .container { max-width: 900px; margin: 0 auto; background: white; padding: 30px; border-radius: 12px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); }
                                    h1 { color: #2c3e50; border-bottom: 2px solid #eee; padding-bottom: 10px; }
                                    .path { font-family: monospace; background: #e9ecef; padding: 8px; border-radius: 4px; word-break: break-all; margin-bottom: 20px; display: block; }
                                    ul { list-style: none; padding: 0; }
                                    li { display: flex; align-items: center; padding: 12px; border-bottom: 1px solid #eee; transition: background 0.2s; }
                                    li:hover { background: #f1f3f5; }
                                    li:last-child { border-bottom: none; }
                                    .icon { font-size: 1.2rem; margin-right: 15px; width: 25px; text-align: center; }
                                    .file-info { flex-grow: 1; }
                                    .file-name { font-weight: 500; text-decoration: none; color: #007bff; }
                                    .file-name:hover { text-decoration: underline; }
                                    .file-meta { font-size: 0.85rem; color: #6c757d; margin-top: 4px; }
                                    .btn-back { display: inline-block; margin-bottom: 20px; text-decoration: none; color: #495057; font-weight: bold; }
                                    .btn-back:before { content: '← '; }
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
                                    a(href = "/?path=$parentPath", classes = "btn-back") { +"Back to parent" }
                                }

                                ul {
                                    val files = currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                                    
                                    if (files == null) {
                                        li {
                                            style = "color: #d9534f; background: #fdf7f7; border: 1px solid #ebccd1; padding: 15px;"
                                            +"⚠️ Permission Denied: Please enable 'All Files Access' in Android Settings for this app."
                                        }
                                    } else if (files.isEmpty()) {
                                        li { +"This folder is empty." }
                                    } else {
                                        files.forEach { file ->
                                            val relativePath = file.absolutePath.removePrefix(root.absolutePath).removePrefix("/")
                                            li {
                                                span(classes = "icon") {
                                                    +if (file.isDirectory) "📁" else when (file.extension.lowercase()) {
                                                        "jpg", "jpeg", "png", "gif", "webp" -> "🖼️"
                                                        "mp3", "wav", "ogg", "m4a", "flac" -> "🎵"
                                                        "mp4", "mkv", "mov", "avi" -> "🎬"
                                                        "pdf" -> "📕"
                                                        "zip", "rar", "7z" -> "📦"
                                                        "txt", "doc", "docx" -> "📄"
                                                        else -> "📄"
                                                    }
                                                }
                                                div(classes = "file-info") {
                                                    if (file.isDirectory) {
                                                        a(href = "/?path=$relativePath", classes = "file-name") { +file.name }
                                                        div(classes = "file-meta") { +"Folder" }
                                                    } else {
                                                        a(href = "/download?path=$relativePath", classes = "file-name") { +file.name }
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
                        call.respondText("File not found at: ${file.absolutePath}", status = HttpStatusCode.NotFound)
                    }
                }
            }
        }.start(wait = false)

        val ip = getLocalIpAddress()
        onStarted("http://$ip:$port")
    }

    fun stop() {
        server?.stop(1000, 2000)
    }

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
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "127.0.0.1"
    }
}
