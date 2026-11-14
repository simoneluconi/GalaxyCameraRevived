// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gctransfer

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Answers mDNS queries for one name, e.g. "gc100.local", with the camera's Wi-Fi address, so
 * the page opens by name from Windows, macOS, Linux and phones. Android 14's own mDNS stack
 * only advertises a random "Android_…" host name for services, and has no API to choose one.
 *
 * A-records only, no probing: a second camera of the same model on one network would answer
 * for the same name. Runs only while the transfer is on.
 */
class MdnsResponder(name: String, private val address: Inet4Address) {

    private val host = "$name.local"
    @Volatile private var socket: MulticastSocket? = null
    /** The Wi-Fi subnet's prefix length, for [onLink]. */
    @Volatile private var prefix = 24

    /** Off the main thread, like [stop]: both use the network. */
    fun start() {
        val iface = NetworkInterface.getByInetAddress(address)
        prefix = iface?.interfaceAddresses?.firstOrNull { it.address == address }?.networkPrefixLength?.toInt() ?: 24
        val s = MulticastSocket(null)
        try {
            // Shared with the system's own mDNS socket: multicast reaches every socket on the port
            s.reuseAddress = true
            s.bind(InetSocketAddress(PORT))
            iface?.let { s.networkInterface = it }
            s.joinGroup(InetSocketAddress(GROUP, PORT), iface)
            s.timeToLive = 255
        } catch (e: Exception) {
            s.close()
            throw e
        }
        socket = s
        Thread({ serve(s) }, "transfer-mdns").start()
        // Announce, so caches that saw an older address catch up (RFC 6762 §8.3: twice, a second apart)
        Thread({
            try {
                for (i in 0 until 2) {
                    if (socket !== s) break
                    send(s, response(0, null, TTL), GROUP_ADDRESS)
                    Thread.sleep(1000)
                }
            } catch (e: InterruptedException) {
                // Nothing to clean up
            }
        }, "transfer-mdns-announce").start()
    }

    fun stop() {
        val s = socket ?: return
        socket = null
        // A goodbye (TTL 0), so the name stops resolving to an address that no longer serves
        send(s, response(0, null, 0), GROUP_ADDRESS)
        s.close()
    }

    private fun serve(s: MulticastSocket) {
        val buf = ByteArray(9000)
        while (socket === s) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (e: IOException) {
                break
            }
            // Only the Wi-Fi's own link (RFC 6762 §11): the socket is bound on every interface
            if (!onLink(p.address)) continue
            val q = runCatching { parse(buf, p.length) }.getOrNull() ?: continue
            // A legacy resolver (not from port 5353) wants a plain unicast DNS reply with its id
            val legacy = p.port != PORT
            val to = if (legacy || q.unicast) InetSocketAddress(p.address, p.port) else GROUP_ADDRESS
            send(s, response(if (legacy) q.id else 0, if (legacy) q.question else null, TTL, cacheFlush = !legacy), to)
        }
    }

    /** In the Wi-Fi's IPv4 subnet. */
    private fun onLink(from: InetAddress): Boolean {
        if (from !is Inet4Address) return false
        val a = from.address
        val b = address.address
        val bits = prefix.coerceIn(0, 32)
        for (i in 0 until 4) {
            val n = (bits - i * 8).coerceIn(0, 8)
            val mask = (0xFF shl (8 - n)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
        }
        return true
    }

    private fun send(s: MulticastSocket, data: ByteArray, to: InetSocketAddress) {
        try {
            s.send(DatagramPacket(data, data.size, to))
        } catch (e: IOException) {
            Log.d(TAG, "mdns send: $e")
        }
    }

    private class Query(val id: Int, val question: ByteArray, val unicast: Boolean)

    /** A query that asks for our name's A (or ANY) record, or null. */
    private fun parse(b: ByteArray, len: Int): Query? {
        if (len < 12) return null
        val flags = u16(b, 2)
        if (flags and 0x8000 != 0) return null // a response, not a query
        val qd = u16(b, 4)
        var pos = 12
        repeat(qd) {
            val start = pos
            val (name, end) = readName(b, len, pos) ?: return null
            if (end + 4 > len) return null
            val type = u16(b, end)
            val cls = u16(b, end + 2)
            pos = end + 4
            if (name.equals(host, ignoreCase = true) && (type == TYPE_A || type == TYPE_ANY) && (cls and 0x7FFF) == CLASS_IN) {
                return Query(u16(b, 0), b.copyOfRange(start, pos), cls and 0x8000 != 0)
            }
        }
        return null
    }

    /** The dotted name at [start] and where the record goes on; follows compression pointers. */
    private fun readName(b: ByteArray, len: Int, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var pos = start
        var end = -1
        var jumps = 0
        while (true) {
            if (pos >= len) return null
            val l = b[pos].toInt() and 0xFF
            when {
                l == 0 -> {
                    if (end < 0) end = pos + 1
                    return sb.toString() to end
                }
                l and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= len || ++jumps > 8) return null
                    if (end < 0) end = pos + 2
                    pos = ((l and 0x3F) shl 8) or (b[pos + 1].toInt() and 0xFF)
                }
                else -> {
                    if (pos + 1 + l > len) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    sb.append(String(b, pos + 1, l, Charsets.UTF_8))
                    pos += 1 + l
                }
            }
        }
    }

    private fun response(id: Int, question: ByteArray?, ttl: Int, cacheFlush: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream(64)
        fun u16(v: Int) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
        u16(id)
        u16(0x8400) // a response, authoritative
        u16(if (question != null) 1 else 0)
        u16(1)
        u16(0)
        u16(0)
        question?.let { out.write(it) }
        for (label in host.split('.')) {
            val bytes = label.toByteArray()
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        u16(TYPE_A)
        u16(if (cacheFlush) CLASS_IN or 0x8000 else CLASS_IN)
        u16(ttl shr 16)
        u16(ttl and 0xFFFF)
        u16(4)
        out.write(address.address)
        return out.toByteArray()
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private companion object {
        const val TAG = "GcGallery"
        const val PORT = 5353
        val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
        val GROUP_ADDRESS = InetSocketAddress(GROUP, PORT)
        const val TYPE_A = 1
        const val TYPE_ANY = 255
        const val CLASS_IN = 1
        /** RFC 6762's default for host records. */
        const val TTL = 120
    }
}
