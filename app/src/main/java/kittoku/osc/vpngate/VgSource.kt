package kittoku.osc.vpngate

import android.content.Context
import android.text.Html
import kittoku.osc.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL


internal class VgList(
    val servers: List<VgServer>,
    val updatedAt: Long,   // unix seconds, 0 if unknown
    val source: String,
)

/**
 * Where the server list comes from, in order:
 *  1. servers.json that the repo's Action refreshes every 30 min (several CDN paths,
 *     because any one of them can be filtered)
 *  2. vpngate.net directly, parsed on the phone
 *  3. the last list that worked, cached on disk
 */
internal object VgSource {
    private const val CACHE_FILE = "vg_servers.json"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"

    private fun repoUrls(): List<Pair<String, String>> {
        val repo = BuildConfig.SERVER_REPO
        if (repo.isBlank()) return emptyList()
        return listOf(
            "jsdelivr" to "https://cdn.jsdelivr.net/gh/$repo@data/servers.json",
            "github" to "https://raw.githubusercontent.com/$repo/data/servers.json",
            "fastly" to "https://fastly.jsdelivr.net/gh/$repo@data/servers.json",
            "gcore" to "https://gcore.jsdelivr.net/gh/$repo@data/servers.json",
        )
    }

    fun loadCache(context: Context): VgList? {
        return try {
            val f = File(context.filesDir, CACHE_FILE)
            if (!f.exists()) null else parseJson(f.readText(), "saved")
        } catch (_: Exception) {
            null
        }
    }

    /** Blocking; call from Dispatchers.IO. */
    fun fetch(context: Context): VgList? {
        for ((name, url) in repoUrls()) {
            val text = httpGet(url) ?: continue
            val list = try { parseJson(text, name) } catch (_: Exception) { null } ?: continue
            if (list.servers.isNotEmpty()) {
                saveCache(context, text)
                return list
            }
        }

        fetchDirect()?.also { return it }

        return loadCache(context)
    }

    private fun saveCache(context: Context, text: String) {
        try {
            File(context.filesDir, CACHE_FILE).writeText(text)
        } catch (_: Exception) { }
    }

    private fun parseJson(text: String, source: String): VgList {
        val root = JSONObject(text)
        val arr = root.getJSONArray("servers")
        val out = ArrayList<VgServer>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                VgServer(
                    host = o.getString("h"),
                    port = o.optInt("p", 443),
                    ip = o.optString("ip"),
                    cc = o.optString("cc"),
                    country = o.optString("c"),
                    score = o.optLong("s"),
                    ping = o.optInt("ping"),
                    speed = o.optLong("spd"),
                    sessions = o.optInt("ses"),
                )
            )
        }
        return VgList(out, root.optLong("t"), source)
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).also {
                it.connectTimeout = 8_000
                it.readTimeout = 15_000
                it.setRequestProperty("User-Agent", UA)
            }
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ---- direct fallback, same logic as tools/fetch_servers.py ----

    private val sstpRegex = Regex(
        """SSTP\s+Hostname\s*:?\s*([A-Za-z0-9.-]+\.opengw\.net)(?::(\d+))?"""
    )

    private fun fetchDirect(): VgList? {
        val html = httpGet("https://www.vpngate.net/en/") ?: httpGet("http://www.vpngate.net/en/") ?: return null
        val csv = httpGet("https://www.vpngate.net/api/iphone/") ?: httpGet("http://www.vpngate.net/api/iphone/") ?: ""

        @Suppress("DEPRECATION")
        val flat = Html.fromHtml(html.replace(Regex("<[^>]+>"), " ")).toString()

        val sstp = LinkedHashMap<String, Pair<String, Int>>()
        sstpRegex.findAll(flat).forEach { m ->
            val host = m.groupValues[1].lowercase()
            val port = m.groupValues[2].toIntOrNull() ?: 443
            sstp[host.substringBefore('.')] = host to port
        }
        if (sstp.isEmpty()) return null

        val rows = HashMap<String, List<String>>()
        csv.lineSequence().forEach { line ->
            if (line.isEmpty() || line[0] == '*' || line[0] == '#') return@forEach
            val p = line.split(',')
            if (p.size >= 15) rows[p[0].trim().lowercase()] = p
        }

        val servers = sstp.map { (short, hp) ->
            val p = rows[short]
            VgServer(
                host = hp.first,
                port = hp.second,
                ip = p?.get(1)?.trim() ?: "",
                cc = p?.get(6)?.trim()?.uppercase() ?: "",
                country = p?.get(5)?.trim() ?: "",
                score = p?.get(2)?.toLongOrNull() ?: 0,
                ping = p?.get(3)?.toIntOrNull() ?: 0,
                speed = p?.get(4)?.toLongOrNull() ?: 0,
                sessions = p?.get(7)?.toIntOrNull() ?: 0,
            )
        }.sortedByDescending { it.score }

        return VgList(servers, System.currentTimeMillis() / 1000, "vpngate.net")
    }
}
