package kittoku.osc.vpngate


internal data class VgServer(
    val host: String,      // full DDNS name, e.g. public-vpn-229.opengw.net
    val port: Int,
    val ip: String,
    val cc: String,
    val country: String,
    val score: Long,
    val ping: Int,         // measured by VPN Gate from Japan, not from the user
    val speed: Long,       // bits per second
    val sessions: Int,
) {
    val label: String
        get() = if (port == 443) host else "$host:$port"

    val shortName: String
        get() = host.substringBefore('.')
}

internal enum class ProbeState { IDLE, TESTING, OK, FAILED }

internal data class ProbeResult(
    val state: ProbeState,
    val ms: Int = -1,
    val viaIp: Boolean = false,   // DNS for the hostname failed, the raw IP worked
    val useSni: Boolean = false,  // shared account: only the custom SNI got through
    val reason: String = "",
)

internal class VgRow(val server: VgServer) {
    var result = ProbeResult(ProbeState.IDLE)
}

internal fun flagOf(cc: String): String {
    if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return "🌐"
    val base = 0x1F1E6 - 'A'.code
    return String(Character.toChars(base + cc[0].code)) + String(Character.toChars(base + cc[1].code))
}

internal fun formatSpeed(bps: Long): String {
    if (bps <= 0) return ""
    val mbps = bps / 1_000_000.0
    return if (mbps >= 10) "${mbps.toInt()} Mbps" else String.format("%.1f Mbps", mbps)
}
