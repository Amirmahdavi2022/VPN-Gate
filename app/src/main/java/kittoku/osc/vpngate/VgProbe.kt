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

    fun probe(s: VgServer): ProbeResult = attempt(s.host, s.port, s.ip, s.host)

    /**
     * For a shared account. With a custom SNI it first tries the handshake carrying that
     * name (what the channel suggests for getting past the filter), then the plain one.
     * The certificate chain and the real host name are checked in both cases, exactly
     * like the client does on connect, so "OK" here means the real connection can work.
     */
    fun probeAccount(host: String, port: Int, sni: String): ProbeResult {
        if (sni.isNotBlank() && sni != host) {
            val withSni = attempt(host, port, "", sni)
            if (withSni.state == ProbeState.OK) return withSni.copy(useSni = true)
            val plain = attempt(host, port, "", host)
            return if (plain.state == ProbeState.OK) plain else withSni
        }
        return attempt(host, port, "", host)
    }

    private fun attempt(host: String, port: Int, ip: String, sniName: String): ProbeResult {
        val start = SystemClock.elapsedRealtime()
        var viaIp = false
        val raw = Socket()

        try {
            val address = try {
                InetAddress.getByName(host)
            } catch (e: UnknownHostException) {
                if (ip.isBlank()) return fail("dns")
                viaIp = true
                InetAddress.getByName(ip)
            }

            raw.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT)
            raw.soTimeout = READ_TIMEOUT

            val ssl = SSLContext.getDefault().socketFactory
                .createSocket(raw, host, port, true) as SSLSocket

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ssl.sslParameters = ssl.sslParameters.also {
                    it.serverNames = listOf(SNIHostName(sniName))
                }
            }

            try {
                ssl.startHandshake()
            } catch (e: SSLHandshakeException) {
                return fail(if (hasCertCause(e)) "cert" else "tls reset")
            }

            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                return fail("cert")
            }

            val request = arrayOf(
                "SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1",
                "Content-Length: 18446744073709551615",
                "Host: $host",
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
