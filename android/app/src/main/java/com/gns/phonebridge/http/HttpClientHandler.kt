package com.gns.phonebridge.http

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder

/** Handles one HTTP GET/HEAD request end-to-end, then closes the connection. */
class HttpClientHandler(
    private val socket: Socket,
    private val rootDir: File,
    private val onLog: (String) -> Unit,
) : Runnable {

    @Volatile private var closed = false

    fun forceClose() {
        closed = true
        runCatching { socket.close() }
    }

    override fun run() {
        try {
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawPath = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            if (method != "GET" && method != "HEAD") {
                writeStatus(501, "Not Implemented")
                return
            }

            val decodedPath = URLDecoder.decode(rawPath.substringBefore('?'), "UTF-8")
            val target = resolvePath(decodedPath)
            val headOnly = method == "HEAD"
            when {
                target == null -> writeStatus(404, "Not Found")
                target.isDirectory -> serveDirectoryListing(target, decodedPath, headOnly)
                target.isFile -> serveFile(target, headers["range"], headOnly)
                else -> writeStatus(404, "Not Found")
            }
        } catch (e: Exception) {
            onLog("HTTP session error: ${e.message}")
        } finally {
            forceClose()
        }
    }

    private fun resolvePath(path: String): File? {
        val relative = path.trimStart('/')
        val candidate = if (relative.isEmpty()) rootDir else File(rootDir, relative)
        val rootCanonical = rootDir.canonicalFile
        val candidateCanonical = candidate.canonicalFile
        return if (candidateCanonical.path == rootCanonical.path ||
            candidateCanonical.path.startsWith(rootCanonical.path + File.separator)
        ) candidateCanonical else null
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var previous = -1
        var current: Int
        var readAny = false
        while (true) {
            current = input.read()
            if (current == -1) return if (readAny) buffer.toString(Charsets.ISO_8859_1.name()) else null
            readAny = true
            if (previous == '\r'.code && current == '\n'.code) {
                val bytes = buffer.toByteArray()
                return String(bytes, 0, bytes.size - 1, Charsets.ISO_8859_1)
            }
            buffer.write(current)
            previous = current
        }
    }

    private fun serveDirectoryListing(dir: File, urlPath: String, headOnly: Boolean) {
        val entries = dir.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
            ?: emptyList()
        val normalizedPath = if (urlPath.endsWith("/")) urlPath else "$urlPath/"

        val html = buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<title>Conet FR</title><style>")
            append("body{font-family:sans-serif;background:#0b1220;color:#eee;padding:16px;margin:0}")
            append("h2{margin:0 0 12px}")
            append("a{color:#8ecbff;text-decoration:none;display:block;padding:10px 4px;border-bottom:1px solid #22314a}")
            append("a:hover{background:#152238}")
            append("</style></head><body><h2>Conet FR</h2>")

            if (normalizedPath != "/") {
                val trimmed = normalizedPath.trimEnd('/')
                val parent = trimmed.substringBeforeLast('/', "")
                val parentHref = if (parent.isEmpty()) "/" else "$parent/"
                append("<a href=\"$parentHref\">.. (subir)</a>")
            }
            for (f in entries) {
                val displayName = f.name + if (f.isDirectory) "/" else ""
                val encodedName = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
                val href = normalizedPath + encodedName + if (f.isDirectory) "/" else ""
                append("<a href=\"${escapeHtml(href)}\">${escapeHtml(displayName)}</a>")
            }
            append("</body></html>")
        }

        val bytes = html.toByteArray(Charsets.UTF_8)
        val out = socket.getOutputStream()
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        if (!headOnly) out.write(bytes)
        out.flush()
    }

    private fun serveFile(file: File, rangeHeader: String?, headOnly: Boolean) {
        val length = file.length()
        val mime = guessMime(file.name)
        var start = 0L
        var end = length - 1
        var status = 200
        var statusText = "OK"

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").split("-", limit = 2)
            spec.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { start = it.toLong() }
            spec.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { end = it.toLong() }
            if (start > end || end >= length) end = length - 1
            status = 206
            statusText = "Partial Content"
        }

        val contentLength = (end - start + 1).coerceAtLeast(0)
        val out = socket.getOutputStream()
        val headerText = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: $mime\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (status == 206) append("Content-Range: bytes $start-$end/$length\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headerText.toByteArray(Charsets.US_ASCII))

        if (!headOnly && contentLength > 0) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buffer = ByteArray(64 * 1024)
                var remaining = contentLength
                while (remaining > 0 && !closed) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = raf.read(buffer, 0, toRead)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
        out.flush()
    }

    private fun writeStatus(code: Int, text: String) {
        val body = "<html><body><h3>$code $text</h3></body></html>".toByteArray(Charsets.UTF_8)
        val out = socket.getOutputStream()
        val header = "HTTP/1.1 $code $text\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "mp4" -> "video/mp4"
            "3gp" -> "video/3gpp"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain; charset=utf-8"
            "html", "htm" -> "text/html; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
