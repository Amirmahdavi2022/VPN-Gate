package kittoku.osc.vpngate

import android.os.Build
import android.os.SystemClock
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket


/**
 * Tests one server from the user's own network, doing the same first steps the real
 * client does: TCP, TLS with the normal certificate checks, then the SSTP HTTP request.
 * A server only counts as OK if it answers that request with 200, so "port open but
 * not SSTP" and "TLS reset by the filter" both show up as failures.
 */
internal object VgProbe {
    private const val CONNECT_TIMEOUT = 5_000
    private const val READ_TIMEOUT = 6_000

    fun probe(s: VgServer): ProbeResult {
        val start = SystemClock.elapsedRealtime()
        var viaIp = false
        val raw = Socket()

        try {
            val address = try {
                InetAddress.getByName(s.host)
            } catch (e: UnknownHostException) {
                if (s.ip.isBlank()) return fail("dns")
                viaIp = true
                InetAddress.getByName(s.ip)
            }

            raw.connect(InetSocketAddress(address, s.port), CONNECT_TIMEOUT)
            raw.soTimeout = READ_TIMEOUT

            val ssl = SSLContext.getDefault().socketFactory
                .createSocket(raw, s.host, s.port, true) as SSLSocket

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ssl.sslParameters = ssl.sslParameters.also {
                    it.serverNames = listOf(SNIHostName(s.host))
                }
            }

            try {
                ssl.startHandshake()
            } catch (e: SSLHandshakeException) {
                return fail(if (hasCertCause(e)) "cert" else "tls reset")
            }

            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(s.host, ssl.session)) {
                return fail("cert")
            }

            val request = arrayOf(
                "SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1",
                "Content-Length: 18446744073709551615",
                "Host: ${s.host}",
                "SSTPCORRELATIONID: {${UUID.randomUUID()}}"
            ).joinToString("\r\n", postfix = "\r\n\r\n")

            ssl.outputStream.also {
                it.write(request.toByteArray(Charsets.US_ASCII))
                it.flush()
            }

            val input = ssl.inputStream
            val sb = StringBuilder()
            while (sb.length < 4096 && !sb.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) break
                sb.append(b.toChar())
            }

            val status = sb.lineSequence().firstOrNull() ?: ""
            if (!status.contains(" 200")) return fail("not sstp")

            val ms = (SystemClock.elapsedRealtime() - start).toInt()
            return ProbeResult(ProbeState.OK, ms, viaIp)
        } catch (_: SocketTimeoutException) {
            return fail("timeout")
        } catch (_: ConnectException) {
            return fail("refused")
        } catch (_: NoRouteToHostException) {
            return fail("no route")
        } catch (_: IOException) {
            return fail("reset")
        } catch (_: Exception) {
            return fail("error")
        } finally {
            try { raw.close() } catch (_: Exception) { }
        }
    }

    private fun fail(reason: String) = ProbeResult(ProbeState.FAILED, reason = reason)

    private fun hasCertCause(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is CertPathValidatorException || t is java.security.cert.CertificateException) return true
            t = t.cause
        }
        return false
    }
}
