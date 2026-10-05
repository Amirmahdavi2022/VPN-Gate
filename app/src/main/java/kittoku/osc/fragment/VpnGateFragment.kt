package kittoku.osc.fragment

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
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
import android.widget.Toast
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
import kittoku.osc.vpngate.ProbeState
import kittoku.osc.vpngate.VgList
import kittoku.osc.vpngate.VgProbe
import kittoku.osc.vpngate.VgRotation
import kittoku.osc.vpngate.VgRow
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


private const val PROBE_LIMIT = 60          // top servers by VPN Gate score that get tested
private const val PROBE_PARALLEL = 16
private const val RESULTS_FRESH_MS = 10 * 60 * 1000L

// small promo card at the bottom of the screen
private const val CHANNEL = "parsv2r"
private const val CHANNEL_LINE = "📢 کانال تلگرام ما — آموزش و آپدیت‌ها"


@SuppressLint("NotifyDataSetChanged")
class VpnGateFragment : Fragment(R.layout.fragment_vpngate) {
    private var _b: FragmentVpngateBinding? = null
    private val b get() = _b!!

    private lateinit var prefs: SharedPreferences
    private val rows = mutableListOf<VgRow>()
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
        if (rows.isEmpty()) loadList(thenProbe = true) else probeAll()
    }

    private fun refresh() {
        if (isOn) {
            Toast.makeText(requireContext(), "Disconnect first, so the test measures your own connection", Toast.LENGTH_LONG).show()
            return
        }
        connectAfterProbe = false
        loadList(thenProbe = true)
    }

    private fun loadList(thenProbe: Boolean) {
        if (loadJob?.isActive == true) return
        probeJob?.cancel()

        if (rows.isEmpty()) setStatus("Getting server list…", "")
        b.vgProgress.isIndeterminate = true
        b.vgProgress.visibility = View.VISIBLE

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) { VgSource.fetch(requireContext().applicationContext) }
            b.vgProgress.visibility = View.INVISIBLE

            if (fetched == null || fetched.servers.isEmpty()) {
                if (rows.isEmpty()) {
                    setStatus("Couldn't get the server list", "Check your connection and tap Refresh")
                }
                connectAfterProbe = false
                return@launch
            }

            fill(fetched)
            if (thenProbe) probeAll() else renderState()
        }
    }

    private fun fill(newList: VgList) {
        list = newList
        rows.clear()
        newList.servers.take(PROBE_LIMIT).forEach { rows.add(VgRow(it)) }
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
        rows.forEach { it.result = it.result.copy(state = ProbeState.IDLE, reason = "") }
        adapter.notifyDataSetChanged()

        val total = rows.size
        var done = 0
        var ok = 0
        b.vgProgress.isIndeterminate = false
        b.vgProgress.max = total
        b.vgProgress.setProgressCompat(0, false)
        b.vgProgress.visibility = View.VISIBLE
        setStatus("Testing servers on your network…", "0 / $total")

        val snapshot = rows.toList()
        probeJob = viewLifecycleOwner.lifecycleScope.launch {
            val gate = Semaphore(PROBE_PARALLEL)
            coroutineScope {
                snapshot.forEach { row ->
                    launch {
                        gate.withPermit {
                            row.result = row.result.copy(state = ProbeState.TESTING)
                            notifyRow(row)

                            row.result = withContext(Dispatchers.IO) { VgProbe.probe(row.server) }

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
            rows.sortWith(compareBy<VgRow>(
                { if (it.result.state == ProbeState.OK) 0 else 1 },
                { if (it.result.state == ProbeState.OK) it.result.ms else 0 },
                { -it.server.score },
            ))
            adapter.notifyDataSetChanged()
            b.vgList.scrollToPosition(0)

            lastProbeAt = SystemClock.elapsedRealtime()
            b.vgProgress.visibility = View.INVISIBLE

            val best = rows.firstOrNull { it.result.state == ProbeState.OK }
            if (best == null) {
                connectAfterProbe = false
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
        val targets = ordered.map {
            VgRotation.Target(it.server.host, it.server.port, it.server.ip, it.result.viaIp)
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

    // ---------------------------------------------------------------- rendering

    private fun renderState() {
        val binding = _b ?: return

        if (isOn) {
            binding.vgConnect.text = "Disconnect"
            val t = VgRotation.current(prefs)
            if (t != null) {
                val row = rows.firstOrNull { it.server.host == t.host }
                val where = row?.let { "${flagOf(it.server.cc)} ${it.server.country}" } ?: ""
                val backups = VgRotation.remaining(prefs)
                setStatus("VPN on", listOf(where, t.host, if (backups > 0) "$backups backups ready" else "")
                    .filter { it.isNotBlank() }.joinToString("  ·  "))
            } else {
                setStatus("VPN on", "Using the server from the HOME tab")
            }
        } else {
            binding.vgConnect.text = "Quick connect"
            if (probeJob?.isActive != true && loadJob?.isActive != true) {
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
            ).filter { it.isNotBlank() }.joinToString("  ·  ")

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
                    holder.v.vgResult.text = "${r.ms} ms"
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
