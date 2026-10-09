package kittoku.osc.vpngate


/**
 * An SSTP account someone shares as a plain text message (Telegram channels mostly):
 * a list of host names, one username and password for all of them, sometimes a custom SNI.
 *
 * The parser is deliberately forgiving: emoji, labels in any case, "Host Names:" headers,
 * links to the channel and blank lines are all fine. It only needs a username, a password
 * and at least one host.
 */
internal data class MyAccount(
    val hosts: List<MyHost>,
    val username: String,
    val password: String,
    val sni: String,   // empty = send the real host name
) {
    internal data class MyHost(val host: String, val port: Int) {
        val label: String
            get() = if (port == 443) host else "$host:$port"
    }

    companion object {
        private val KEY_USER = Regex("""^(?:user\s*name|username|user|login|یوزرنیم|نام کاربری|یوزر)$""", RegexOption.IGNORE_CASE)
        private val KEY_PASS = Regex("""^(?:pass\s*word|password|pass|pwd|پسورد|رمز عبور|رمز)$""", RegexOption.IGNORE_CASE)
        private val KEY_SNI = Regex("""^(?:custom\s*sni|sni|server\s*name|اس ان آی)$""", RegexOption.IGNORE_CASE)
        private val KEY_PORT = Regex("""^(?:port|پورت)$""", RegexOption.IGNORE_CASE)

        private val URL = Regex("""(?:https?://|tg://)\S+|\bt\.me/\S+|@[A-Za-z0-9_]+""", RegexOption.IGNORE_CASE)
        private val HOST = Regex("""(?<![A-Za-z0-9._-])((?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,24}|\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?(?![A-Za-z0-9-])""")

        /** Returns null when the text doesn't hold a username, a password and at least one host. */
        fun parse(text: String): MyAccount? {
            var user = ""
            var pass = ""
            var sni = ""
            var defaultPort = 443
            val hostLines = mutableListOf<String>()

            text.lines().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach

                val colon = line.indexOfFirst { it == ':' || it == '：' }
                if (colon > 0) {
                    // drop emoji and decorations in front of the label
                    val key = line.substring(0, colon)
                        .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
                        .trim()
                        .replace(Regex("""\s+"""), " ")
                    val value = line.substring(colon + 1).trim().trim('`', '"', '\'', '«', '»').trim()

                    when {
                        KEY_USER.matches(key) -> { if (value.isNotEmpty()) user = value; return@forEach }
                        KEY_PASS.matches(key) -> { if (value.isNotEmpty()) pass = value; return@forEach }
                        KEY_SNI.matches(key) -> { sni = value.lowercase(); return@forEach }
                        KEY_PORT.matches(key) -> {
                            value.toIntOrNull()?.takeIf { it in 1..65535 }?.also { defaultPort = it }
                            return@forEach
                        }
                    }
                }

                hostLines.add(line)
            }

            if (user.isEmpty() || pass.isEmpty()) return null

            val seen = LinkedHashSet<String>()
            val hosts = mutableListOf<MyHost>()
            hostLines.forEach { line ->
                val clean = URL.replace(line, " ")
                HOST.findAll(clean).forEach { m ->
                    val host = m.groupValues[1].lowercase()
                    val port = m.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
                    if (host == sni) return@forEach
                    if (isIp(host) && host.split('.').any { it.toInt() > 255 }) return@forEach
                    if (seen.add("$host:$port")) hosts.add(MyHost(host, port))
                }
            }

            if (hosts.isEmpty()) return null
            return MyAccount(hosts, user, pass, sni)
        }

        private fun isIp(s: String) = s.all { it.isDigit() || it == '.' }

        /**
         * Best guess at a country from names like us1., uk4., de1. so the list can show a flag.
         * Returns "" when the first label doesn't look like that.
         */
        fun countryOf(host: String): String {
            val m = Regex("""^([a-z]{2})[-_]?\d*$""").find(host.substringBefore('.')) ?: return ""
            val cc = m.groupValues[1].uppercase().let { if (it == "UK") "GB" else it }
            return if (cc in java.util.Locale.getISOCountries()) cc else ""
        }
    }
}
