// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.transfer

import android.content.Context
import android.os.Build
import android.util.Log
import com.simoneluconi.gcgallery.data.MediaIndex
import com.simoneluconi.gcgallery.data.Photo
import com.simoneluconi.gcgallery.thumbs.ThumbLoader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import org.json.JSONObject

/**
 * A small HTTPS server for the Wi-Fi transfer page: the page itself, the photo list, the
 * gallery's thumbnails and the files, streamed, and moving photos to the trash. Only what the
 * gallery's index holds is served (no trash, nothing outside MediaStore). Open to the local
 * network unless the user set a password on the camera ([TransferAuth]).
 */
class WebServer(
    private val context: Context,
    private val ssl: SSLContext,
    private val auth: TransferAuth,
    private val photos: () -> List<Photo>,
    private val thumbs: ThumbLoader,
    private val events: Events,
) {
    /** Called on the server's threads. */
    interface Events {
        fun onRequest()
        fun onSent(name: String, bytes: Long)
        /** Moves these files to MediaStore's trash; false when it is not allowed. */
        fun trash(photos: List<Photo>): Boolean
    }

    /** Files being streamed right now; the service does not stop for idleness while > 0. */
    val streaming = AtomicInteger()

    @Volatile private var running = false
    private var socket: SSLServerSocket? = null
    private val open: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val failures = AtomicInteger()
    @Volatile private var lockedUntil = 0L
    private val page by lazy { context.assets.open("web/index.html").use { it.readBytes() } }
    // Browsers open up to 6 connections and keep them alive; more wait in the queue
    private val pool = ThreadPoolExecutor(WORKERS, WORKERS, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16)) { r ->
        Thread(r, "transfer-http").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /** Listens on [address] only, the Wi-Fi one: never on mobile data. Throws if the port is taken. */
    fun start(address: Inet4Address, port: Int) {
        val ss = ssl.serverSocketFactory.createServerSocket() as SSLServerSocket
        ss.reuseAddress = true
        ss.enabledProtocols = ss.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        ss.bind(InetSocketAddress(address, port), 16)
        socket = ss
        running = true
        Thread({ accept(ss) }, "transfer-accept").start()
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        // Blocked reads and writes end when their socket closes
        for (s in open.toList()) runCatching { s.close() }
        pool.shutdownNow()
    }

    private fun accept(ss: SSLServerSocket) {
        while (running) {
            val s = try {
                ss.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept: $e")
                break
            }
            try {
                pool.execute { serve(s) }
            } catch (e: RejectedExecutionException) {
                runCatching { s.close() }
            }
        }
    }

    private fun serve(s: Socket) {
        open.add(s)
        try {
            s.soTimeout = IDLE_TIMEOUT_MS
            s.tcpNoDelay = true
            val input = BufferedInputStream(s.getInputStream(), 8192)
            val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
            while (running) {
                val req = Request.read(input) ?: break
                events.onRequest()
                val keep = handle(req, out) && !req.close
                out.flush()
                if (!keep) break
            }
        } catch (e: IOException) {
            // The browser went away, a timeout, a TLS warning it did not accept: nothing to do
            Log.d(TAG, "connection: $e")
        } catch (e: BadRequest) {
            Log.d(TAG, "bad request: ${e.message}")
        } catch (e: InterruptedException) {
            // stop() while a wrong PIN was being slowed down
        } catch (e: Exception) {
            // Never let it reach the pool thread: an uncaught exception would end the process
            Log.w(TAG, "request failed", e)
        } finally {
            open.remove(s)
            runCatching { s.close() }
        }
    }

    // --- Routes

    /** False to close the connection after the response. */
    private fun handle(req: Request, out: OutputStream): Boolean {
        val path = req.path
        // A POST must carry our own header: a page on another site cannot add it without a CORS
        // preflight, which this server never answers. With the page open to the network, that
        // keeps other sites from trashing photos through the user's browser.
        if (req.method == "POST" && req.headers["x-gc"] != "1") return send(out, 403, JSON, """{"error":"origin"}""".toByteArray())
        return when {
            path == "/" || path == "/index.html" -> send(out, 200, "text/html; charset=utf-8", page, PAGE_HEADERS)
            path == "/api/login" && req.method == "POST" -> login(req, out)
            !authorised(req) -> send(out, 401, JSON, """{"error":"password"}""".toByteArray())
            path == "/api/trash" && req.method == "POST" -> trash(req, out)
            req.method != "GET" -> send(out, 405, "text/plain", "method".toByteArray())
            path == "/api/photos" -> send(out, 200, JSON, list().toByteArray(), listOf("Cache-Control: no-store"))
            path.startsWith("/thumb/") -> thumb(path.removePrefix("/thumb/"), out)
            path.startsWith("/file/") -> file(path.removePrefix("/file/"), req, out)
            else -> send(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun authorised(req: Request): Boolean {
        if (!auth.hasPassword) return true
        val cookie = req.headers["cookie"] ?: return false
        return cookie.split(';').any { c ->
            val kv = c.trim()
            kv.startsWith("$COOKIE=") && auth.valid(kv.substringAfter('='))
        }
    }

    private fun login(req: Request, out: OutputStream): Boolean {
        if (!auth.hasPassword) return send(out, 204, null, ByteArray(0))
        if (System.currentTimeMillis() < lockedUntil) return send(out, 429, JSON, """{"error":"locked"}""".toByteArray())
        val typed = runCatching { JSONObject(String(req.body)).getString("password") }.getOrDefault("")
        if (typed.isNotEmpty() && auth.check(typed)) {
            failures.set(0)
            // 400 days, the most Chrome keeps a cookie: the browser asks once
            return send(out, 204, null, ByteArray(0), listOf("Set-Cookie: $COOKIE=${auth.newToken()}; Path=/; Max-Age=34560000; HttpOnly; Secure; SameSite=Strict"))
        }
        // Slow guessing down, then stop it for a minute
        Thread.sleep(1000)
        if (failures.incrementAndGet() >= MAX_FAILURES) {
            failures.set(0)
            lockedUntil = System.currentTimeMillis() + LOCK_MS
        }
        return send(out, 403, JSON, """{"error":"wrong"}""".toByteArray())
    }

    /** {"ids":[…]}: those photos, each with its DNG, to MediaStore's trash (GC Gallery can restore them). */
    private fun trash(req: Request, out: OutputStream): Boolean {
        val ids = runCatching {
            val a = JSONObject(String(req.body)).getJSONArray("ids")
            (0 until a.length()).map { a.getLong(it) }.toSet()
        }.getOrNull() ?: return send(out, 400, JSON, """{"error":"ids"}""".toByteArray())
        val list = photos().filter { it.id in ids }
        if (list.isEmpty()) return send(out, 200, JSON, """{"trashed":0}""".toByteArray())
        if (!events.trash(list)) return send(out, 403, JSON, """{"error":"denied"}""".toByteArray())
        return send(out, 200, JSON, """{"trashed":${list.size}}""".toByteArray())
    }

    /** Newest first, as the gallery shows them; a JPEG's DNG rides along as "raw". */
    private fun list(): String {
        val sb = StringBuilder(64 * 1024)
        sb.append("{\"camera\":").append(json(Build.MODEL)).append(",\"password\":").append(auth.hasPassword).append(",\"photos\":[")
        var first = true
        for (p in photos()) {
            if (!first) sb.append(',')
            first = false
            sb.append("{\"id\":").append(p.id)
                .append(",\"name\":").append(json(p.name))
                .append(",\"mime\":").append(json(p.mime))
                .append(",\"size\":").append(p.size)
                .append(",\"taken\":").append(p.dateTaken)
                .append(",\"m\":").append(p.dateModified)
                .append(",\"w\":").append(p.width)
                .append(",\"h\":").append(p.height)
                .append(",\"folder\":").append(json(p.folder))
                .append(",\"storage\":").append(json(if (MediaIndex.scopeOf(p) == MediaIndex.Scope.SD) "sd" else "internal"))
            p.raw?.let { r ->
                sb.append(",\"raw\":{\"id\":").append(r.id)
                    .append(",\"name\":").append(json(r.name))
                    .append(",\"size\":").append(r.size).append('}')
            }
            sb.append('}')
        }
        return sb.append("]}").toString()
    }

    private fun thumb(arg: String, out: OutputStream): Boolean {
        val p = arg.toLongOrNull()?.let { id -> photos().firstOrNull { it.id == id } }
            ?: return send(out, 404, "text/plain", "no photo".toByteArray())
        // The gallery's own disk cache: upright, 256 px, made once
        val f = thumbs.file(p) ?: return send(out, 404, "text/plain", "no thumbnail".toByteArray())
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return send(out, 404, "text/plain", "no thumbnail".toByteArray())
        return send(out, 200, "image/jpeg", bytes, listOf("Cache-Control: private, max-age=604800"))
    }

    private fun file(arg: String, req: Request, out: OutputStream): Boolean {
        val id = arg.toLongOrNull()
        val p = photos().asSequence().flatMap { it.files() }.firstOrNull { it.id == id }
            ?: return send(out, 404, "text/plain", "no photo".toByteArray())
        val pfd = runCatching { context.contentResolver.openFileDescriptor(p.uri, "r") }.getOrNull()
            ?: return send(out, 404, "text/plain", "cannot open".toByteArray())
        pfd.use {
            val size = pfd.statSize
            val range = req.headers["range"]?.let { parseRange(it, size) }
            val start = range?.first ?: 0L
            val end = range?.second ?: (size - 1)
            val length = end - start + 1
            val headers = mutableListOf(
                "Content-Disposition: attachment; filename=\"${p.name.replace("\"", "")}\"; filename*=UTF-8''${URLEncoder.encode(p.name, "UTF-8").replace("+", "%20")}",
                "Accept-Ranges: bytes",
                "Cache-Control: no-store",
            )
            if (range != null) headers.add("Content-Range: bytes $start-$end/$size")
            head(out, if (range != null) 206 else 200, p.mime, length, headers)
            streaming.incrementAndGet()
            try {
                FileInputStream(pfd.fileDescriptor).use { input ->
                    if (start > 0) input.channel.position(start)
                    copy(input, out, length)
                }
                out.flush()
            } finally {
                streaming.decrementAndGet()
                events.onRequest()
            }
            if (range == null) events.onSent(p.name, length)
        }
        return true
    }

    /** One "bytes=a-b", "bytes=a-" or "bytes=-n" range; null for anything else (the whole file). */
    private fun parseRange(h: String, size: Long): Pair<Long, Long>? {
        val m = Regex("^bytes=(\\d*)-(\\d*)$").find(h.trim()) ?: return null
        val a = m.groupValues[1].toLongOrNull()
        val b = m.groupValues[2].toLongOrNull()
        val r = when {
            a != null -> a to minOf(b ?: (size - 1), size - 1)
            b != null -> maxOf(0L, size - b) to size - 1
            else -> return null
        }
        return r.takeIf { it.first <= it.second && it.first < size }
    }

    private fun copy(input: InputStream, out: OutputStream, length: Long) {
        // Big reads: every read is a round trip through MediaProvider's FUSE (kernel 3.4)
        val buf = ByteArray(256 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("file shorter than its size")
            out.write(buf, 0, n)
            left -= n
        }
    }

    // --- HTTP

    private fun send(out: OutputStream, code: Int, type: String?, body: ByteArray, extra: List<String> = emptyList()): Boolean {
        head(out, code, type, body.size.toLong(), extra)
        out.write(body)
        return true
    }

    private fun head(out: OutputStream, code: Int, type: String?, length: Long, extra: List<String>) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(code).append(' ').append(REASONS[code] ?: "Status").append("\r\n")
        type?.let { sb.append("Content-Type: ").append(it).append("\r\n") }
        sb.append("Content-Length: ").append(length).append("\r\n")
        for (h in extra) sb.append(h).append("\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nConnection: keep-alive\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun json(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    private class BadRequest(message: String) : Exception(message)

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray, val close: Boolean) {
        companion object {
            /** The next request on a kept-alive connection, or null when the browser closed it. */
            fun read(input: InputStream): Request? {
                val line = readLine(input) ?: return null
                val parts = line.split(' ')
                if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw BadRequest(line.take(80))
                val headers = HashMap<String, String>()
                while (true) {
                    val h = readLine(input) ?: throw BadRequest("headers cut short")
                    if (h.isEmpty()) break
                    if (headers.size >= MAX_HEADERS) throw BadRequest("too many headers")
                    val colon = h.indexOf(':')
                    if (colon > 0) headers[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length !in 0..MAX_BODY) throw BadRequest("body of $length")
                val body = ByteArray(length)
                var got = 0
                while (got < length) {
                    val n = input.read(body, got, length - got)
                    if (n < 0) throw BadRequest("body cut short")
                    got += n
                }
                val close = headers["connection"].equals("close", ignoreCase = true) || parts[2] == "HTTP/1.0"
                return Request(parts[0], parts[1].substringBefore('?'), headers, body, close)
            }

            private fun readLine(input: InputStream): String? {
                val sb = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c < 0) return if (sb.isEmpty()) null else throw BadRequest("line cut short")
                    if (c == '\n'.code) break
                    if (c != '\r'.code) sb.append(c.toChar())
                    if (sb.length > MAX_LINE) throw BadRequest("line too long")
                }
                return sb.toString()
            }
        }
    }

    companion object {
        private const val TAG = "GcGallery"
        private const val WORKERS = 6
        private const val IDLE_TIMEOUT_MS = 20_000
        private const val MAX_LINE = 8192
        private const val MAX_HEADERS = 64
        private const val MAX_BODY = 4096
        private const val MAX_FAILURES = 10
        private const val LOCK_MS = 60_000L
        private const val COOKIE = "gcs"
        private const val JSON = "application/json"
        private val PAGE_HEADERS = listOf(
            "Cache-Control: no-cache",
            "Content-Security-Policy: default-src 'self'; img-src 'self' blob:; style-src 'unsafe-inline'; script-src 'unsafe-inline'",
        )
        private val REASONS = mapOf(
            200 to "OK", 204 to "No Content", 206 to "Partial Content", 400 to "Bad Request", 401 to "Unauthorized",
            403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 429 to "Too Many Requests",
        )
    }
}
