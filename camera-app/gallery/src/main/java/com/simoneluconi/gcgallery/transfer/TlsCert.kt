// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.transfer

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The camera's own TLS certificate. `showDirectoryPicker` needs a secure context, and plain
 * http to a LAN address is not one; a self-signed certificate is, once the browser's warning
 * is accepted (checked in Chrome and Edge 157, 2026-11-14).
 *
 * Made once and kept, so the warning comes back only when the camera's address changes: the
 * name and the address are in the certificate, so a PC that trusts it sees no warning at all.
 */
object TlsCert {

    /**
     * For [host] ("gc100.local") and [address]; a new one when either changed. Off the main
     * thread: making the key, and loading the PKCS12 file, take seconds on this CPU.
     */
    fun context(context: Context, host: String, address: Inet4Address): SSLContext {
        val file = File(context.filesDir, FILE)
        val ip = address.hostAddress ?: ""
        val ks = load(file)?.takeIf { namesOf(it) == setOf(host, ip) } ?: make(host, address).also { ks ->
            file.outputStream().use { ks.store(it, PASSWORD) }
            Log.i(TAG, "new certificate for $host, $ip")
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASSWORD)
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    private fun load(file: File): KeyStore? = if (!file.exists()) null else runCatching {
        KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD) } }
    }.getOrNull()

    /** The DNS names and IP addresses in the certificate's subjectAltName. */
    private fun namesOf(ks: KeyStore): Set<String> {
        val cert = ks.getCertificate(ALIAS) as? X509Certificate ?: return emptySet()
        return runCatching { cert.subjectAlternativeNames?.mapNotNull { it[1] as? String }?.toSet() }.getOrNull() ?: emptySet()
    }

    private fun make(host: String, address: Inet4Address): KeyStore {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val pair = kpg.generateKeyPair()
        val name = Der.seq(Der.set(Der.seq(Der.oid(OID_CN), Der.utf8("GC Gallery on ${Build.MODEL}"))))
        val now = System.currentTimeMillis()
        val ecdsaSha256 = Der.seq(Der.oid(OID_ECDSA_SHA256))
        // dNSName [2] and iPAddress [7]
        val san = Der.seq(Der.oid(OID_SAN), Der.octets(Der.seq(Der.tlv(0x82, host.toByteArray(Charsets.US_ASCII)), Der.tlv(0x87, address.address))))
        val tbs = Der.seq(
            Der.tlv(0xA0, Der.int(BigInteger.valueOf(2))),
            Der.int(BigInteger(1, ByteArray(16).also { SecureRandom().nextBytes(it) })),
            ecdsaSha256,
            name,
            Der.seq(Der.time(now - DAY), Der.time(now + VALID_DAYS * DAY)),
            name,
            pair.public.encoded,
            Der.tlv(0xA3, Der.seq(san)),
        )
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(tbs)
            sign()
        }
        val der = Der.seq(tbs, ecdsaSha256, Der.bits(sig))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
        return KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, pair.private, PASSWORD, arrayOf(cert))
        }
    }

    /** Just enough DER for one certificate. */
    private object Der {
        fun tlv(tag: Int, body: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            write(tag)
            val n = body.size
            when {
                n < 0x80 -> write(n)
                n < 0x100 -> { write(0x81); write(n) }
                else -> { write(0x82); write(n shr 8); write(n and 0xFF) }
            }
            write(body)
        }.toByteArray()

        fun seq(vararg parts: ByteArray) = tlv(0x30, concat(parts))
        fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))
        fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
        fun utf8(s: String) = tlv(0x0C, s.toByteArray())
        fun octets(b: ByteArray) = tlv(0x04, b)
        fun bits(b: ByteArray) = tlv(0x03, byteArrayOf(0) + b)

        fun oid(dotted: String): ByteArray {
            val arcs = dotted.split('.').map { it.toLong() }
            val out = ByteArrayOutputStream()
            out.write((arcs[0] * 40 + arcs[1]).toInt())
            // Base 128, most significant group first, the high bit set on all but the last
            for (a in arcs.drop(2)) {
                val groups = ArrayList<Int>()
                var v = a
                do {
                    groups.add((v and 0x7F).toInt())
                    v = v shr 7
                } while (v > 0)
                for (i in groups.indices.reversed()) out.write(groups[i] or if (i > 0) 0x80 else 0)
            }
            return tlv(0x06, out.toByteArray())
        }

        /** UTCTime up to 2049, GeneralizedTime after, as RFC 5280 asks. */
        fun time(ms: Long): ByteArray {
            val utc = ms < UTC_TIME_END
            val f = SimpleDateFormat(if (utc) "yyMMddHHmmss'Z'" else "yyyyMMddHHmmss'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return tlv(if (utc) 0x17 else 0x18, f.format(Date(ms)).toByteArray())
        }

        /** 2050-01-01T00:00:00Z. */
        private const val UTC_TIME_END = 2524608000000L

        private fun concat(parts: Array<out ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            for (p in parts) out.write(p)
            return out.toByteArray()
        }
    }

    private const val TAG = "GcGallery"
    private const val FILE = "transfer-tls.p12"
    private const val ALIAS = "gc"
    /** The file is private to the app; the password is only what PKCS12 requires. */
    private val PASSWORD = "gcgallery".toCharArray()
    private const val VALID_DAYS = 3650L
    private const val DAY = 24L * 3600 * 1000
    private const val OID_CN = "2.5.4.3"
    private const val OID_SAN = "2.5.29.17"
    private const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"
}
