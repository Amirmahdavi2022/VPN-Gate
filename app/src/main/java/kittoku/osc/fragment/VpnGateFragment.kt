package kittoku.osc.fragment

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kittoku.osc.R
import kittoku.osc.databinding.FragmentVpngateBinding
import kittoku.osc.databinding.ItemVgServerBinding
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.checkPreferences
import kittoku.osc.preference.toastInvalidSetting
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.SstpVpnService
import kittoku.osc.vpngate.MyAccount
import kittoku.osc.vpngate.ProbeState
import kittoku.osc.vpngate.VgList
import kittoku.osc.vpngate.VgProbe
import kittoku.osc.vpngate.VgRotation
import kittoku.osc.vpngate.VgRow
import kittoku.osc.vpngate.VgServer
import kittoku.osc.vpngate.VgSource
import kittoku.osc.vpngate.flagOf
import kittoku.osc.vpngate.formatSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale


private const val PROBE_LIMIT = 60          // top servers by VPN Gate score that get tested
private const val PROBE_PARALLEL = 16
private const val RESULTS_FRESH_MS = 10 * 60 * 1000L

// shared account pasted from a channel message, kept as the raw text and parsed on load
private const val KEY_ACCOUNT_TEXT = "_MA_TEXT"
private const val KEY_MODE = "_VG_MODE"
private const val MODE_ACCOUNT = "account"

// small promo card at the bottom of the screen
private const val CHANNEL = "parsv2r"
private const val CHANNEL_LINE = "📢 کانال تلگرام ما — آموزش و آپدیت‌ها"


@SuppressLint("NotifyDataSetChanged")
class VpnGateFragment : Fragment(R.layout.fragment_vpngate) {
    private var _b: FragmentVpngateBinding? = null
    private val b get() = _b!!

    private lateinit var prefs: SharedPreferences
    private val gateRows = mutableListOf<VgRow>()
    private val accountRows = mutableListOf<VgRow>()
    private var account: MyAccount? = null
    private var accountMode = false

    // the list on screen: VPN Gate servers or the pasted account's servers
    private val rows: MutableList<VgRow>
        get() = if (accountMode) accountRows else gateRows
    private val adapter = Adapter()

    private var list: VgList? = null
    private var loadJob: Job? = null
    private var probeJob: Job? = null
    private var lastProbeAt = 0L
    private var connectAfterProbe = false
    private var animateNextFill = true

    private val preparationLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(ACTION_VPN_CONNECT)
        } else {
            setStatus("VPN permission was not granted", "")
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == OscPrefKey.ROOT_STATE.name || key == OscPrefKey.HOME_HOSTNAME.name) {
            _b?.root?.post { renderState() }
        }
    }

    private val isOn: Boolean
        get() = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = FragmentVpngateBinding.bind(view)
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        b.vgList.layoutManager = LinearLayoutManager(requireContext())
        b.vgList.adapter = adapter
        b.vgList.itemAnimator?.changeDuration = 120

        b.vgConnect.setOnClickListener { onConnectClicked() }
        b.vgRefresh.setOnClickListener { refresh() }
        b.vgPaste.setOnClickListener { pasteAccount() }

        accountMode = prefs.getString(KEY_MODE, "") == MODE_ACCOUNT
        loadAccount()
        b.vgMode.check(if (accountMode) R.id.vgModeAccount else R.id.vgModeGate)
        b.vgMode.addOnButtonCheckedListener { _, id, checked ->
            if (checked) setMode(id == R.id.vgModeAccount)
        }
        applyModeUi()

        b.vgChannelText.text = CHANNEL_LINE
        b.vgChannelHandle.text = "@$CHANNEL"
        b.vgChannel.setOnClickListener { openChannel() }

        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        // show the saved list instantly, then pull a fresh one
        VgSource.loadCache(requireContext())?.also { fill(it) }
        loadList(thenProbe = false)
        renderState()
    }

    override fun onDestroyView() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        _b = null
        super.onDestroyView()
    }

    // ---------------------------------------------------------------- actions

    private fun onConnectClicked() {
        if (isOn) {
            connectAfterProbe = false
            startVpnService(ACTION_VPN_DISCONNECT)
            return
        }

        if (accountMode && account == null) {
            pasteAccount()
            return
        }

        if (probeJob?.isActive == true || loadJob?.isActive == true) {
            connectAfterProbe = true
            setStatus("Finding a working server…", "Will connect as soon as one passes")
            return
        }

        val fresh = SystemClock.elapsedRealtime() - lastProbeAt < RESULTS_FRESH_MS
        val best = rows.firstOrNull { it.result.state == ProbeState.OK }
        if (fresh && best != null) {
            connectFrom(best)
            return
        }

        connectAfterProbe = true
        if (rows.isEmpty() && !accountMode) loadList(thenProbe = true) else probeAll()
    }

    private fun refresh() {
        if (isOn) {
            Toast.makeText(requireContext(), "Disconnect first, so the test measures your own connection", Toast.LENGTH_LONG).show()
            return
        }
        connectAfterProbe = false
        if (accountMode) {
            if (account == null) pasteAccount() else probeAll()
        } else {
            loadList(thenProbe = true)
        }
    }

    private fun loadList(thenProbe: Boolean) {
        if (loadJob?.isActive == true) return
        probeJob?.cancel()

        if (!accountMode) {
            if (gateRows.isEmpty()) setStatus("Getting server list…", "")
            b.vgProgress.isIndeterminate = true
            b.vgProgress.visibility = View.VISIBLE
        }

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) { VgSource.fetch(requireContext().applicationContext) }
            if (!accountMode) b.vgProgress.visibility = View.INVISIBLE

            if (fetched == null || fetched.servers.isEmpty()) {
                if (gateRows.isEmpty() && !accountMode) {
                    setStatus("Couldn't get the server list", "Check your connection and tap Refresh")
                }
                connectAfterProbe = false
                return@launch
            }

            fill(fetched)
            if (thenProbe && !accountMode) probeAll() else renderState()
        }
    }

    private fun fill(newList: VgList) {
        list = newList
        gateRows.clear()
        newList.servers.take(PROBE_LIMIT).forEach { gateRows.add(VgRow(it)) }
        if (accountMode) return
        lastProbeAt = 0L

        if (animateNextFill) {
            b.vgList.scheduleLayoutAnimation()
            animateNextFill = false
        }
        adapter.notifyDataSetChanged()
        renderUpdated()
    }

    private fun probeAll() {
        if (rows.isEmpty()) return
        if (isOn) {
            setStatus("Disconnect to test servers", "Tests must run on your own connection")
            return
        }

        probeJob?.cancel()
        val target = rows
        val acc = if (accountMode) account else null
        target.forEach { it.result = it.result.copy(state = ProbeState.IDLE, reason = "") }
        adapter.notifyDataSetChanged()

        val total = target.size
        var done = 0
        var ok = 0
        b.vgProgress.isIndeterminate = false
        b.vgProgress.max = total
        b.vgProgress.setProgressCompat(0, false)
        b.vgProgress.visibility = View.VISIBLE
        setStatus("Testing servers on your network…", "0 / $total")

        val snapshot = target.toList()
        probeJob = viewLifecycleOwner.lifecycleScope.launch {
            val gate = Semaphore(PROBE_PARALLEL)
            coroutineScope {
                snapshot.forEach { row ->
                    launch {
                        gate.withPermit {
                            row.result = row.result.copy(state = ProbeState.TESTING)
                            notifyRow(row)

                            row.result = withContext(Dispatchers.IO) {
                                if (acc != null) VgProbe.probeAccount(row.server.host, row.server.port, acc.sni)
                                else VgProbe.probe(row.server)
                            }

                            done += 1
                            if (row.result.state == ProbeState.OK) ok += 1
                            b.vgProgress.setProgressCompat(done, true)
                            setStatus("Testing servers on your network…", "$done / $total  ·  $ok working")
                            notifyRow(row)
                        }
                    }
                }
            }

            // fastest working first, then the rest by VPN Gate's own score
            target.sortWith(compareBy<VgRow>(
                { if (it.result.state == ProbeState.OK) 0 else 1 },
                { if (it.result.state == ProbeState.OK) it.result.ms else 0 },
                { -it.server.score },
            ))
            adapter.notifyDataSetChanged()
            b.vgList.scrollToPosition(0)

            lastProbeAt = SystemClock.elapsedRealtime()
            b.vgProgress.visibility = View.INVISIBLE

            val best = target.firstOrNull { it.result.state == ProbeState.OK }
            if (best == null) {
                connectAfterProbe = false
                val certOnly = acc != null && target.all { it.result.reason == "cert" }
                if (certOnly) {
                    setStatus("The servers answered, but their certificate didn't check out",
                        "Not connecting, so nobody can read your traffic in between")
                    return@launch
                }
                setStatus("No server got through right now", "Try again in a few minutes, or switch between Wi-Fi and mobile data")
                return@launch
            }

            if (connectAfterProbe) {
                connectAfterProbe = false
                connectFrom(best)
            } else {
                setStatus("$ok of $total servers work for you", "Tap Quick connect, or pick one below")
            }
        }
    }

    /** Connects to [first], keeping every other working server queued as backup. */
    private fun connectFrom(first: VgRow) {
        val working = rows.filter { it.result.state == ProbeState.OK }
        val ordered = listOf(first) + working.filter { it !== first }
        val acc = if (accountMode) account else null
        val targets = ordered.map {
            if (acc != null) {
                VgRotation.Target(it.server.host, it.server.port, "", false,
                    acc.username, acc.password, if (it.result.useSni) acc.sni else "")
            } else {
                VgRotation.Target(it.server.host, it.server.port, it.server.ip, it.result.viaIp)
            }
        }
        VgRotation.start(prefs, targets)

        checkPreferences(prefs)?.also {
            toastInvalidSetting(it, requireContext())
            return
        }

        setStatus("Connecting…", first.server.label)
        VpnService.prepare(requireContext())?.also {
            preparationLauncher.launch(it)
        } ?: startVpnService(ACTION_VPN_CONNECT)
    }

    private fun startVpnService(action: String) {
        val intent = Intent(requireContext(), SstpVpnService::class.java).setAction(action)
        if (action == ACTION_VPN_CONNECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }

    private fun openChannel() {
        val app = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$CHANNEL"))
        try {
            startActivity(app)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$CHANNEL")))
            } catch (_: ActivityNotFoundException) { }
        }
    }

    // ---------------------------------------------------------------- shared account

    private fun setMode(toAccount: Boolean) {
        if (toAccount == accountMode) return
        probeJob?.cancel()
        connectAfterProbe = false
        lastProbeAt = 0L
        accountMode = toAccount
        prefs.edit().putString(KEY_MODE, if (toAccount) MODE_ACCOUNT else "gate").apply()
        applyModeUi()
    }

    private fun applyModeUi() {
        val binding = _b ?: return
        binding.vgTitle.text = if (accountMode) "MY ACCOUNT · SSTP" else "VPN GATE · SSTP"
        binding.vgPaste.visibility = if (accountMode) View.VISIBLE else View.GONE
        binding.vgRefresh.text = if (accountMode) "Test" else "Refresh & test"
        binding.vgProgress.visibility = View.INVISIBLE
        adapter.notifyDataSetChanged()
        renderUpdated()
        renderState()
    }

    private fun loadAccount() {
        val text = prefs.getString(KEY_ACCOUNT_TEXT, null)
        account = text?.let { MyAccount.parse(it) }
        accountRows.clear()
        account?.hosts?.forEach { h ->
            val cc = MyAccount.countryOf(h.host)
            val country = if (cc.isEmpty()) "" else Locale("", cc).getDisplayCountry(Locale.ENGLISH)
            accountRows.add(VgRow(VgServer(h.host, h.port, "", cc, country, 0L, 0, 0L, 0)))
        }
    }

    /** Takes the account straight from the clipboard; falls back to a box to paste into. */
    private fun pasteAccount() {
        if (isOn) {
            Toast.makeText(requireContext(), "Disconnect first", Toast.LENGTH_SHORT).show()
            return
        }

        val clip = (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(requireContext())?.toString().orEmpty()

        if (MyAccount.parse(clip) != null) {
            saveAccount(clip)
            return
        }

        val input = EditText(requireContext()).also {
            it.hint = "Host names, Username, Password, SNI…"
            it.minLines = 6
            it.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            it.setText(clip)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(requireContext()).also {
            it.setPadding(pad, pad / 2, pad, 0)
            it.addView(input)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Paste the account message")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString()
                if (MyAccount.parse(text) == null) {
                    Toast.makeText(requireContext(),
                        "Couldn't find host names plus a username and password in that text",
                        Toast.LENGTH_LONG).show()
                } else {
                    saveAccount(text)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveAccount(text: String) {
        prefs.edit().putString(KEY_ACCOUNT_TEXT, text).apply()
        loadAccount()
        val a = account ?: return
        Toast.makeText(requireContext(),
            "${a.hosts.size} servers · ${a.username}" + if (a.sni.isNotBlank()) " · SNI ${a.sni}" else "",
            Toast.LENGTH_LONG).show()

        if (!accountMode) {
            b.vgMode.check(R.id.vgModeAccount)   // listener switches the mode
        } else {
            lastProbeAt = 0L
            b.vgList.scheduleLayoutAnimation()
            applyModeUi()
        }
        probeAll()
    }

    // ---------------------------------------------------------------- rendering

    private fun renderState() {
        val binding = _b ?: return

        if (isOn) {
            binding.vgConnect.text = "Disconnect"
            val t = VgRotation.current(prefs)
            if (t != null) {
                val row = (gateRows + accountRows).firstOrNull { it.server.host == t.host }
                val where = row?.let { "${flagOf(it.server.cc)} ${it.server.country}" } ?: ""
                val backups = VgRotation.remaining(prefs)
                setStatus("VPN on", listOf(
                    where,
                    t.host,
                    if (t.sni.isNotBlank()) "SNI ${t.sni}" else "",
                    if (backups > 0) "$backups backups ready" else "",
                ).filter { it.isNotBlank() }.joinToString("  ·  "))
            } else {
                setStatus("VPN on", "Using the server from the HOME tab")
            }
        } else {
            binding.vgConnect.text = if (accountMode && account == null) "Paste account" else "Quick connect"
            if (accountMode && account == null) {
                setStatus("No account yet", "Copy the account message from Telegram, then tap Paste account")
            } else if (probeJob?.isActive != true && (accountMode || loadJob?.isActive != true)) {
                val ok = rows.count { it.result.state == ProbeState.OK }
                if (lastProbeAt > 0 && ok > 0) {
                    setStatus("$ok servers work for you", "Tap Quick connect, or pick one below")
                } else if (rows.isNotEmpty()) {
                    setStatus("Ready", "${rows.size} SSTP servers · tap Quick connect")
                }
            }
        }
    }

    private fun renderUpdated() {
        if (accountMode) {
            val a = account
            b.vgUpdated.text = if (a == null) "" else listOf(
                a.username,
                if (a.sni.isNotBlank()) "SNI ${a.sni}" else "",
            ).filter { it.isNotBlank() }.joinToString("  ·  ")
            return
        }
        val l = list ?: return
        val text = if (l.updatedAt > 0) {
            val mins = ((System.currentTimeMillis() / 1000 - l.updatedAt) / 60).coerceAtLeast(0)
            val age = when {
                mins < 1 -> "just now"
                mins < 60 -> "$mins min ago"
                mins < 60 * 24 -> "${mins / 60} h ago"
                else -> "${mins / 60 / 24} d ago"
            }
            "List updated $age"
        } else {
            "List loaded"
        }
        b.vgUpdated.text = text
    }

    private fun setStatus(title: String, detail: String) {
        val binding = _b ?: return
        binding.vgStatus.text = title
        binding.vgDetail.text = detail
        binding.vgDetail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun notifyRow(row: VgRow) {
        val i = rows.indexOf(row)
        if (i >= 0) adapter.notifyItemChanged(i)
    }

    private inner class Holder(val v: ItemVgServerBinding) : RecyclerView.ViewHolder(v.root)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(ItemVgServerBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            val s = row.server
            val ctx = holder.itemView.context

            holder.v.vgFlag.text = flagOf(s.cc)
            holder.v.vgHost.text = s.label
            holder.v.vgMeta.text = listOf(
                s.country,
                if (s.sessions > 0) "${s.sessions} users" else "",
                formatSpeed(s.speed),
            ).filter { it.isNotBlank() }.joinToString("  ·  ").ifEmpty { "SSTP" }

            val r = row.result
            when (r.state) {
                ProbeState.IDLE -> {
                    holder.v.vgResult.text = ""
                }
                ProbeState.TESTING -> {
                    holder.v.vgResult.text = "testing…"
                    holder.v.vgResult.setTextColor(ContextCompat.getColor(ctx, R.color.vg_bad))
                }
                ProbeState.OK -> {
                    holder.v.vgResult.text = if (r.useSni) "${r.ms} ms · SNI" else "${r.ms} ms"
                    val color = when {
                        r.ms < 700 -> R.color.vg_ok
                        r.ms < 1500 -> R.color.vg_mid
                        else -> R.color.vg_bad
                    }
                    holder.v.vgResult.setTextColor(ContextCompat.getColor(ctx, color))
                }
                ProbeState.FAILED -> {
                    holder.v.vgResult.text = r.reason
                    holder.v.vgResult.setTextColor(ContextCompat.getColor(ctx, R.color.vg_bad))
                }
            }

            holder.v.vgHost.alpha = if (r.state == ProbeState.FAILED) 0.55f else 1f
            holder.v.vgMeta.alpha = holder.v.vgHost.alpha

            holder.itemView.setOnClickListener {
                when {
                    isOn -> Toast.makeText(ctx, "Disconnect first", Toast.LENGTH_SHORT).show()
                    row.result.state == ProbeState.OK -> connectFrom(row)
                    row.result.state == ProbeState.FAILED ->
                        Toast.makeText(ctx, "This one didn't get through from your network", Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(ctx, "Tap Refresh & test first", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
