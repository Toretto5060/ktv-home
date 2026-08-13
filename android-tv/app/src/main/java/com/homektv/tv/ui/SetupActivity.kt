package com.homektv.tv.ui

import android.animation.ObjectAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.view.descendants
import androidx.lifecycle.lifecycleScope
import com.homektv.tv.R
import com.homektv.tv.databinding.ActivitySetupBinding
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.DiscoveredServer
import com.homektv.tv.net.LanDiscovery
import com.homektv.tv.net.LanScanner
import com.homektv.tv.net.SavedServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * TV 服务选择页：历史设备、按需扫描的局域网设备，以及手动输入。
 *
 * TV server selection screen for remembered devices, on-demand LAN discovery,
 * and manual server entry.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var config: AppConfig
    private lateinit var discovery: LanDiscovery
    private val scanner = LanScanner()
    private val discovered = linkedMapOf<String, DiscoveredServer>()
    private var scanJob: Job? = null
    private var validationJob: Job? = null
    private var currentValidationButton: Button? = null
    private val rhythmAnimators = mutableListOf<ObjectAnimator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig(this)
        discovery = LanDiscovery(this)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRefresh.setOnClickListener { startScan() }
        binding.btnConnect.setOnClickListener { submitManual() }
        binding.inputHost.setOnEditorActionListener { _, _, _ ->
            submitManual()
            true
        }

        renderHistory()
        startRhythm()
        rebuildFocusChain()
        binding.root.post { firstFocusableView()?.requestFocus() }
    }

    private fun startRhythm() {
        val bars = listOf(binding.rhythmBar1, binding.rhythmBar2, binding.rhythmBar3, binding.rhythmBar4)
        bars.forEachIndexed { index, bar ->
            bar.post {
                bar.pivotY = bar.height.toFloat()
                ObjectAnimator.ofFloat(bar, View.SCALE_Y, 0.45f, 1f).apply {
                    duration = 460L + index * 90L
                    startDelay = index * 70L
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    rhythmAnimators += this
                    start()
                }
            }
        }
    }

    override fun onDestroy() {
        rhythmAnimators.forEach(ObjectAnimator::cancel)
        rhythmAnimators.clear()
        super.onDestroy()
    }

    private fun startScan() {
        scanJob?.cancel()
        discovered.clear()
        binding.lanContainer.removeAllViews()
        binding.txtLanEmpty.setText(R.string.setup_scanning)
        binding.progressScan.apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        binding.txtScanStatus.setText(R.string.setup_scanning)
        // Keep refresh enabled and focused while scanning so the DPAD focus never disappears.
        binding.btnRefresh.requestFocus()

        scanJob = lifecycleScope.launch {
            val servers = discovery.discoverAll(
                onStage = { stage -> runOnUiThread { showStage(stage) } },
                onProgress = { scanned, total -> runOnUiThread {
                    binding.progressScan.isIndeterminate = false
                    binding.progressScan.max = total
                    binding.progressScan.progress = scanned
                    binding.txtScanStatus.text = getString(R.string.setup_scan_progress, scanned, total)
                } },
                onDiscovered = { server -> runOnUiThread { addDiscoveredServer(server) } },
            )
            binding.progressScan.visibility = View.GONE
            binding.txtScanStatus.text = getString(R.string.setup_scan_done, servers.size)
            binding.txtLanEmpty.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
            if (servers.isEmpty()) binding.txtLanEmpty.setText(R.string.setup_lan_empty)
            rebuildFocusChain()
        }
    }

    private fun showStage(stage: LanDiscovery.Stage) {
        binding.progressScan.isIndeterminate = stage != LanDiscovery.Stage.SUBNET
        binding.txtScanStatus.setText(when (stage) {
            LanDiscovery.Stage.MDNS -> R.string.setup_discovering_mdns
            LanDiscovery.Stage.UDP -> R.string.setup_discovering_udp
            LanDiscovery.Stage.SUBNET -> R.string.setup_scanning
        })
    }

    private fun renderHistory() {
        binding.historyContainer.removeAllViews()
        config.savedServers.forEach(::addHistoryServer)
        binding.txtHistoryEmpty.visibility =
            if (config.savedServers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addHistoryServer(server: SavedServer) {
        val row = LayoutInflater.from(this).inflate(
            R.layout.item_history_server, binding.historyContainer, false,
        )
        row.findViewById<TextView>(R.id.txtHistoryName).text = server.name
        row.findViewById<TextView>(R.id.txtHistoryAddress).text = server.hostPort
        row.findViewById<Button>(R.id.btnHistoryConnect).apply {
            id = View.generateViewId()
            setOnClickListener { connect(server, this) }
        }
        row.findViewById<Button>(R.id.btnHistoryDelete).apply {
            id = View.generateViewId()
            setOnClickListener {
                config.removeSavedServer(server.hostPort)
                renderHistory()
                rebuildFocusChain()
                binding.btnRefresh.requestFocus()
            }
        }
        binding.historyContainer.addView(row)
    }

    private fun addDiscoveredServer(server: DiscoveredServer) {
        if (discovered.putIfAbsent(server.hostPort, server) != null) return
        binding.txtLanEmpty.visibility = View.GONE
        val row = LayoutInflater.from(this).inflate(
            R.layout.item_lan_server, binding.lanContainer, false,
        )
        row.findViewById<TextView>(R.id.txtLanName).text = server.name
        row.findViewById<TextView>(R.id.txtLanAddress).text = server.hostPort
        row.findViewById<Button>(R.id.btnLanConnect).apply {
            id = View.generateViewId()
            setOnClickListener { connect(SavedServer(server.hostPort, server.name), this) }
        }
        binding.lanContainer.addView(row)
        rebuildFocusChain()
    }

    private fun submitManual() {
        val raw = binding.inputHost.text.toString()
        val host = AppConfig.normalizeHost(raw)
        if (host == null) {
            Toast.makeText(this, R.string.setup_empty, Toast.LENGTH_SHORT).show()
            return
        }
        // 存储带协议的完整地址用于 API 调用
        config.serverHost = host
        // verifyAndConnect 现在按 server.hostPort 决定目标地址，
        // 所以这里必须把带协议的 host 传下去，不能剥掉。
        // <p>verifyAndConnect uses server.hostPort as the target now — pass
        // the scheme-prefixed host through, don't strip it.
        verifyAndConnect(SavedServer(host, host), binding.btnConnect)
    }

    private fun connect(server: SavedServer, connectButton: Button) {
        verifyAndConnect(server, connectButton)
    }

    private fun verifyAndConnect(server: SavedServer, button: Button) {
        currentValidationButton = button
        button.isEnabled = false
        binding.validatingOverlay.visibility = View.VISIBLE
        binding.txtValidatingHost.text = server.hostPort
        // 始终用当前被点选的 server 决定目标地址，忽略 config.serverHost
        // （后者保留的是上次连接的地址，可能跟当前点选不同 —— 比如
        // 用户先用 LAN IP 连过，后来从历史选了公网域名；如果以旧的 LAN IP
        // 验证通过并写回 prefs，会导致 MainActivity 的 h5Url 用旧地址、扫码出错）。
        // <p>Always use the tapped server for the target address — config.serverHost
        // holds the previous connection and may differ from this tap (e.g. user
        // tapped a public-domain entry after a LAN-IP session; validating and
        // persisting the old LAN IP would make MainActivity's h5Url/QR wrong).
        val hostToValidate: String = when {
            server.hostPort.startsWith("https://", ignoreCase = true) -> server.hostPort
            server.hostPort.startsWith("http://", ignoreCase = true) -> server.hostPort
            server.hostPort.contains("://") -> server.hostPort
            else -> "http://${server.hostPort}"
        }
        binding.txtScanStatus.text = getString(R.string.setup_verifying, server.hostPort)
        validationJob = lifecycleScope.launch {
            if (scanner.validate(hostToValidate)) {
                // 用当前点选的目标地址持久化 serverHost，避免被旧值覆盖
                config.rememberServer(hostToValidate, server.name)
                startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                finish()
            } else {
                binding.validatingOverlay.visibility = View.GONE
                button.isEnabled = true
                button.requestFocus() // 失败时保持焦点在当前按钮
                Toast.makeText(this@SetupActivity, R.string.setup_invalid, Toast.LENGTH_LONG).show()
                binding.txtScanStatus.setText(R.string.setup_scan_idle)
            }
        }
    }

    private fun rebuildFocusChain() {
        val focusables = mutableListOf<View>()
        binding.historyContainer.children.forEach { row ->
            focusables += (row as ViewGroup).descendants.filterIsInstance<Button>().toList()
        }
        focusables += binding.btnRefresh
        binding.lanContainer.children.forEach { row ->
            focusables += (row as ViewGroup).descendants.filterIsInstance<Button>().toList()
        }
        focusables += binding.inputHost
        focusables += binding.btnConnect

        focusables.forEachIndexed { index, view ->
            view.nextFocusUpId = focusables.getOrNull(index - 1)?.id ?: view.id
            view.nextFocusDownId = focusables.getOrNull(index + 1)?.id ?: view.id
            view.setOnFocusChangeListener { focused, hasFocus ->
                if (hasFocus) scrollIntoView(focused)
            }
        }
    }

    private fun firstFocusableView(): View? =
        binding.historyContainer.descendants.filterIsInstance<Button>().firstOrNull()
            ?: binding.btnRefresh

    private fun scrollIntoView(view: View) {
        binding.setupScroll.post {
            val rect = android.graphics.Rect()
            view.getDrawingRect(rect)
            view.requestRectangleOnScreen(rect, true)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 验证蒙版显示时，按返回键取消连接请求，页面保持不变
        if (keyCode == KeyEvent.KEYCODE_BACK &&
            binding.validatingOverlay.visibility == View.VISIBLE) {
            validationJob?.cancel()
            binding.validatingOverlay.visibility = View.GONE
            binding.txtValidatingHost.text = null
            currentValidationButton?.isEnabled = true
            currentValidationButton?.requestFocus()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
