package kittoku.osc.vpngate

import android.content.SharedPreferences
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setIntPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue


/**
 * The ranked list of servers that passed the probe, kept in prefs so the VPN service
 * can move to the next one on its own when the current one drops or never connects.
 *
 * Rotation only applies while the HOME hostname is still the one this queue put there.
 * If the user types their own server on the HOME tab, the queue is ignored.
 */
internal object VgRotation {
    private const val KEY_QUEUE = "_VG_QUEUE"
    private const val KEY_INDEX = "_VG_INDEX"

    // the credentials VPN Gate publishes for every public server
    private const val VG_USER = "vpn"
    private const val VG_PASS = "vpn"

    internal data class Target(val host: String, val port: Int, val ip: String, val viaIp: Boolean) {
        val connectHost: String
            get() = if (viaIp && ip.isNotBlank()) ip else host

        fun encode() = listOf(host, port.toString(), ip, if (viaIp) "1" else "0").joinToString("|")

        companion object {
            fun decode(s: String): Target? {
                val p = s.split('|')
                if (p.size != 4) return null
                return Target(p[0], p[1].toIntOrNull() ?: return null, p[2], p[3] == "1")
            }
        }
    }

    fun start(prefs: SharedPreferences, targets: List<Target>) {
        if (targets.isEmpty()) return
        prefs.edit()
            .putString(KEY_QUEUE, targets.joinToString("\n") { it.encode() })
            .putInt(KEY_INDEX, 0)
            .apply()
        apply(prefs, targets[0])
    }

    /** Moves to the next server. Returns it, or null if the queue is done or not in use. */
    fun advance(prefs: SharedPreferences): Target? {
        val queue = queue(prefs)
        if (queue.isEmpty()) return null

        val index = prefs.getInt(KEY_INDEX, 0)
        val current = queue.getOrNull(index) ?: return null

        // the user switched to their own server on HOME, leave them alone
        if (getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs) != current.connectHost) return null

        val next = queue.getOrNull(index + 1) ?: return null
        prefs.edit().putInt(KEY_INDEX, index + 1).apply()
        apply(prefs, next)
        return next
    }

    fun remaining(prefs: SharedPreferences): Int {
        val queue = queue(prefs)
        return (queue.size - 1 - prefs.getInt(KEY_INDEX, 0)).coerceAtLeast(0)
    }

    fun current(prefs: SharedPreferences): Target? {
        val queue = queue(prefs)
        val t = queue.getOrNull(prefs.getInt(KEY_INDEX, 0)) ?: return null
        return if (getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs) == t.connectHost) t else null
    }

    private fun queue(prefs: SharedPreferences): List<Target> {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        return raw.lines().mapNotNull { Target.decode(it) }
    }

    /** Fills in exactly what users otherwise type by hand from the VPN Gate site. */
    private fun apply(prefs: SharedPreferences, t: Target) {
        setStringPrefValue(t.connectHost, OscPrefKey.HOME_HOSTNAME, prefs)
        setIntPrefValue(t.port, OscPrefKey.SSL_PORT, prefs)
        setStringPrefValue(VG_USER, OscPrefKey.HOME_USERNAME, prefs)
        setStringPrefValue(VG_PASS, OscPrefKey.HOME_PASSWORD, prefs)

        // normal case: connect by name, full hostname check.
        // DNS-failed case: connect by IP, send the real name as SNI; the certificate chain
        // is still validated, only the name match is skipped because we dialled an IP.
        setBooleanPrefValue(!t.viaIp, OscPrefKey.SSL_DO_VERIFY, prefs)
        setBooleanPrefValue(t.viaIp, OscPrefKey.SSL_DO_USE_CUSTOM_SNI, prefs)
        if (t.viaIp) setStringPrefValue(t.host, OscPrefKey.SSL_CUSTOM_SNI, prefs)

        setBooleanPrefValue(false, OscPrefKey.SSL_DO_SPECIFY_CERT, prefs)
        setBooleanPrefValue(false, OscPrefKey.PROXY_DO_USE_PROXY, prefs)
    }
}
