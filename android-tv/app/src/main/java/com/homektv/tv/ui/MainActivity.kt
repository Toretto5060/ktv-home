package com.homektv.tv.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.KeyEvent
import android.widget.Toast
import android.widget.Button
import android.widget.TextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import androidx.annotation.OptIn
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.core.content.FileProvider
import com.homektv.tv.BuildConfig
import com.homektv.tv.R
import com.homektv.tv.databinding.ActivityMainBinding
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.KtvSocket
import com.homektv.tv.net.MediaApi
import com.homektv.tv.net.ApkPackageInfo
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.StandbyContent
import com.homektv.tv.player.PlaybackEngine
import com.homektv.tv.player.EffectPlayer
import com.homektv.tv.player.LrcParser
import com.homektv.tv.player.LyricLine
import com.homektv.tv.player.MicrophoneMonitor
import kotlinx.coroutines.launch
import java.io.File

/**
 * TV 主界面。
 * - 未配置 NAS 地址 → 跳 SetupActivity（详设§12.1）
 * - 已配置 → 连 WS、渲染待机页 TV-01（简化版），并按快照驱动播放（P1.28）
 *
 * 播放驱动（P1.28）：以服务端快照为唯一事实源。
 *   - state=playing 且有 now_playing → 拉详情取文件源 → ExoPlayer 拉流播放，
 *     显示播放层、隐藏待机页；每 1s 上行 progress、播完上行 finished
 *   - state=paused → 暂停
 *   - state=idle / 无 now_playing → 停止播放，回待机页
 *
 * 完整播放页 UI（双行歌词/信息条/进度条 TV-02/03）、双音轨切换（P1.29）、
 * 遥控浮层（TV-04/05）在后续任务叠加。
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : AppCompatActivity(), KtvSocket.Listener {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private lateinit var config: AppConfig
    private lateinit var mediaApi: MediaApi
    private var socket: KtvSocket? = null
    private var engine: PlaybackEngine? = null
    private var effectPlayer: EffectPlayer? = null
    private var effectOverlay: EffectOverlayView? = null
    private var microphoneMonitor: MicrophoneMonitor? = null
    private var microphoneActive = false
    private var releaseCheckInFlight = false
    private var checkedReleaseVersion: String? = null
    private var promptedReleaseVersion: String? = null
    private var pendingUpdateApk: File? = null
    /** 授权重试定时器：network_error 时 30s 后再试一次。 */
    private val authorizeRetryRunnable = Runnable { runAuthorizeCheck() }
    /** 是否正在等待授权结果（防止重试与已成功的请求叠加）。 */
    private var authorizeCheckInFlight = false
    /** 标识当前授权请求是否已在 HTTP 层面发起（WS 连上后置 true，授权结果返回后清 false）。 */
    private var authorizationInFlight = false
    /** 授权是否已完成（approved/pending/blacklisted/room_not_open/idle 任一非 network_error 状态视为完成）。 */
    private var authorizeCompleted = false
    /** 收到 device_idle WS 事件后置 true，忽略后续 HTTP 授权结果（避免 WS 事件和 HTTP 响应竞争导致闪烁）。 */
    private var deviceIdleReceived = false
    /** 待机页数据是否已加载（授权通过后只加载一次，避免重复请求）。 */
    private var standbyLoaded = false
    /** pending 审批截止时间戳（毫秒），null 表示无倒计时。 */
    private var pendingExpiredAtMs: Long? = null
    /** pending 倒计时刷新定时器。 */
    private val pendingCountdownTick = object : Runnable {
        override fun run() {
            updatePendingCountdown()
            binding.root.postDelayed(this, 1_000L)
        }
    }
    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val recordGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (recordGranted) startMicrophoneMonitor()
        else onToast("未授予麦克风权限")
    }
    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val apk = pendingUpdateApk ?: return@registerForActivityResult
        if (packageManager.canRequestPackageInstalls()) installDownloadedApk(apk)
        else onToast("未允许安装此来源的应用")
    }

    /** 当前正在播放的 queueId，用于判断快照是否切了歌。 */
    private var currentQueueId: Long? = null
    private var currentFileId: Long? = null
    private var accompanimentTrackIndex: Int? = null
    private var audioTrackCount: Int = 1
    private var lyricLines: List<LyricLine> = emptyList()
    private var lastLyricIndex = -1
    private var lastProgressReportMs = Long.MIN_VALUE
    private val clock = android.os.Handler(android.os.Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            if (::binding.isInitialized) binding.txtClock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            clock.postDelayed(this, 30_000L)
        }
    }
    private val menuHide = Runnable { binding.remoteMenu.visibility = View.GONE }
    private val progressHide = Runnable { hidePlaybackProgress() }
    private var recommendations: List<com.homektv.tv.net.SongDto> = emptyList()
    private var recommendationOffset = 0
    private val recommendationCovers = mutableMapOf<Long, android.graphics.Bitmap?>()
    private var standbyCarouselEnabled = true
    private var antiBurnEnabled = true
    private var standbyIntervalMs = 8_000L
    private val standbyMotionAnimators = mutableListOf<ObjectAnimator>()
    private var currentPlaybackState = "idle"
    private var hasCurrentSong = false
    private var isFinishing = false
    private var connectionFailed = false
    private var lastStandbyClickTime = 0L
    private var consecutiveStandbyClicks = 0
    private val standbyClickWindowMs = 300L
    private val standbyClickThreshold = 5
    private val standbyTicker = object : Runnable {
        override fun run() {
            if (standbyCarouselEnabled && recommendations.isNotEmpty()) {
                renderRecommendationCards()
                recommendationOffset = (recommendationOffset + 1) % recommendations.size
            }
            binding.standbyPanel.postDelayed(this, standbyIntervalMs)
        }
    }
    private val burnInTicker = object : Runnable {
        override fun run() {
            if (antiBurnEnabled) {
                val step = if (binding.standbyPanel.translationX >= 1f) -1f else 1f
                binding.standbyPanel.translationX = step
                binding.standbyPanel.translationY = -step
            } else {
                binding.standbyPanel.translationX = 0f
                binding.standbyPanel.translationY = 0f
            }
            binding.standbyPanel.postDelayed(this, 60_000L)
        }
    }
    private val standbySettingsTicker = object : Runnable {
        override fun run() {
            // 仅授权通过后才轮询（pending/blacklisted/room_not_open/idle 不暴露房间信息）
            if (!authorizeCompleted) {
                binding.standbyPanel.postDelayed(this, 60_000L)
                return
            }
            lifecycleScope.launch {
                val content = mediaApi.fetchStandbyContent()
                if (authorizeCompleted) applyStandbyContent(content)
            }
            binding.standbyPanel.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig(this)
        val audioPreview = intent.action == "com.homektv.tv.action.AUDIO_PREVIEW" ||
            intent.getBooleanExtra("audio_preview", false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.statusIndicator.setBackgroundResource(R.drawable.status_indicator_yellow)

        if (!audioPreview) {
            // 兜底：如果 serverHost 为空但有历史记录，用第一个
            if (config.serverHost.isNullOrBlank()) {
                config.savedServers.firstOrNull()?.let { server ->
                    val host = server.hostPort
                    if (host.contains("://")) {
                        config.serverHost = host
                    }
                }
            }
            val sh = config.serverHost
            if (sh.isNullOrBlank() || !sh.contains("://")) {
                startActivity(Intent(this, SetupActivity::class.java))
                finish()
                return
            }
            binding.validatingOverlay.visibility = View.VISIBLE
            binding.txtOverlayTitle.text = "正在连接..."
            binding.txtValidatingHost.text = getString(R.string.setup_verifying_server)
        }

        onMainReady(audioPreview)
    }

    private fun onMainReady(audioPreview: Boolean) {
        clock.post(clockTick)

        mediaApi = MediaApi(config)
        if (audioPreview) {
            // 音频预览模式无需授权，启动完整的待机页数据加载
            loadStandbyDataIfApproved()
        } else {
            // 在线模式：待机页数据延迟到 runAuthorizeCheck() 通过后加载，
            // 避免 pending/blacklisted 状态暴露房间信息（QR / standbyContent / 歌曲数）。
            binding.standbyPanel.postDelayed(burnInTicker, 60_000L)
        }
        engine = PlaybackEngine(
            context = this,
            onProgress = { pos ->
                // UI 以高频本地时钟平滑刷新，服务端进度仍保持 1s 上报频率。
                if (lastProgressReportMs == Long.MIN_VALUE || pos - lastProgressReportMs >= 1_000L) {
                    lastProgressReportMs = pos
                    socket?.sendProgress(pos)
                }
                runOnUiThread { updateProgress(pos) }
            },
            onFinished = { socket?.sendFinished() },
            onError = { onPlayError() },
        ).also {
            it.attach(binding.playerView)
            // 遥控音量键在此 ROM 上直达系统媒体会话：监听系统音量变化上行同步服务端
            it.onExternalVolumeChange = { percent ->
                if (percent != currentVolume) sendControl("set_volume", "{\"volume\":$percent}")
            }
        }
        effectPlayer = EffectPlayer()
        microphoneMonitor = MicrophoneMonitor(this) { state ->
            runOnUiThread {
                microphoneActive = state.active
                updateMicrophoneButton()
                state.message?.let(::onToast)
                if (state.active) onToast("麦克风已接入：${state.deviceName}")
            }
        }
        effectOverlay = EffectOverlayView(this).also { overlay ->
            overlay.visibility = View.GONE
            binding.root.addView(
                overlay,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }

        setupRemoteMenu()
        // 根据屏幕高度调整待机页字体大小
        binding.standbyPanel.post { adjustTextSizesForScreen() }
        if (audioPreview) {
            renderAudioPreview()
        } else {
            // 有 serverHost 才会连接；连接失败由 onConnectionChanged 处理跳转
            socket = KtvSocket(config, this).also { it.connect() }
            // 连接超时：15秒未连接成功则跳转 SetupActivity
            binding.root.postDelayed(connectionTimeoutRunnable, 15_000L)
        }
    }

    // 用于取消连接超时定时器
    private val connectionTimeoutRunnable = Runnable {
        if (!connectionFailed && socket != null) {
            Toast.makeText(this, "连接服务器超时", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        }
    }

    private fun renderAudioPreview() {
        showPlayer(audioMode = true)
        binding.imgAudioCover.setImageDrawable(null)
        binding.txtAudioFallback.text = "晴天"
        binding.txtAudioTitle.text = "晴天"
        binding.txtAudioArtist.text = "周杰伦"
        binding.txtAudioNext.text = "接下来  海阔天空 · Beyond"
        binding.txtAudioLyricCurrent.setLine(LyricLine(0L, "童年的荡秋千 随记忆一直晃到现在"), 10_000L)
        binding.txtAudioLyricCurrent.updatePlayback(4_200L, false)
        binding.txtAudioLyricNext.text = "吹着前奏望着天空"
        binding.audioProgress.progress = 420
        binding.txtAudioElapsed.text = "01:42"
        binding.txtAudioDuration.text = "04:03"
    }

    /** 根据屏幕宽度动态调整待机页文字大小，确保横屏时内容完整显示 */
    private fun adjustTextSizesForScreen() {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels / displayMetrics.density

        // 横屏模式下，如果高度不足基准值进行缩放
        val baseHeight = 450f
        if (screenHeight < baseHeight) {
            // 使用更平缓的缩放曲线，最小缩放到 0.85
            val rawScale = screenHeight / baseHeight
            val scale = 0.85f + (rawScale * 0.15f)
            if (rawScale < 1f) {
                // 顶部 Logo
                binding.imgStandbyLogo?.let {
                    val lp = it.layoutParams
                    lp.width = (120 * scale).toInt()
                    lp.height = (120 * scale).toInt()
                    it.layoutParams = lp
                }
                // 品牌名称
                binding.txtBrandName?.let {
                    (it as? TextView)?.textSize = 26f * scale
                }
                // 顶部信息栏
                binding.txtPhones?.let {
                    (it as? TextView)?.textSize = 18f * scale
                }
                binding.txtClock?.let {
                    (it as? TextView)?.textSize = 24f * scale
                }
                // 排队中文字
                binding.txtQueueCount?.let {
                    (it as? TextView)?.textSize = 15f * scale
                }
                // 主标题 "今晚开唱" - 缩小
                binding.txtStandbyWelcome.textSize = 34f
                // 副标题
                binding.txtStandbySubtitle?.let {
                    (it as? TextView)?.textSize = 22f * scale
                }
                // 统计信息
                listOf(binding.txtLibraryStat, binding.txtPlayedStat).forEach {
                    (it as? TextView)?.textSize = 15f * scale
                }
                // 二维码面板 - 二维码图片缩小更多
                binding.qrPanel?.let { panel ->
                    if (panel is ViewGroup) {
                        val vg = panel
                        for (i in 0 until vg.childCount) {
                            val child = vg.getChildAt(i)
                            when (child) {
                                is ImageView -> {
                                    val lp = child.layoutParams
                                    lp.width = (lp.width * scale * 0.85f).toInt()
                                    lp.height = (lp.height * scale * 0.85f).toInt()
                                    child.layoutParams = lp
                                }
                                is TextView -> {
                                    // 二维码上方文字（"扫码点歌"、"微信扫一扫"）缩小更多
                                    val id = child.id
                                    if (id == R.id.qrTitle) {
                                        child.textSize = 17f * scale
                                    } else {
                                        child.textSize = 10f * scale
                                    }
                                }
                            }
                        }
                    }
                }
                // 底部推荐歌单
                binding.recommendationRow?.let { row ->
                    row.layoutParams = row.layoutParams.apply {
                        height = (82 * scale).toInt()
                    }
                }
                // 底部播放栏 - 专辑封面
                binding.imgAudioCover?.let {
                    val lp = it.layoutParams
                    lp.height = (lp.height * scale).toInt()
                    it.layoutParams = lp
                }
                // 底部播放栏 - 歌名
                binding.txtAudioTitle?.let {
                    (it as? TextView)?.textSize = 30f * scale
                }
                binding.txtAudioArtist?.let {
                    (it as? TextView)?.textSize = 19f * scale
                }
            }
        }
    }

    override fun onDestroy() {
        isFinishing = true
        standbyMotionAnimators.forEach(ObjectAnimator::cancel)
        standbyMotionAnimators.clear()
        socket?.close()
        clock.removeCallbacks(clockTick)
        clock.removeCallbacks(progressHide)
        binding.root.removeCallbacks(authorizeRetryRunnable)
        binding.root.removeCallbacks(pendingCountdownTick)
        binding.standbyPanel.removeCallbacks(standbyTicker)
        binding.standbyPanel.removeCallbacks(burnInTicker)
        binding.standbyPanel.removeCallbacks(standbySettingsTicker)
        socket = null
        engine?.detach(binding.playerView)
        engine?.release()
        engine = null
        effectPlayer?.release()
        effectPlayer = null
        effectOverlay?.clear()
        effectOverlay = null
        microphoneMonitor?.release()
        microphoneMonitor = null
        super.onDestroy()
    }

    /** 顶层 BACK 双击退出：此 ROM 会在 Activity 退到后台 1s 内强杀进程，单击防误触。 */
    private var lastBackAt = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && binding.playerView.visibility == View.VISIBLE) {
            showPlaybackProgress()
        }
        // 验证蒙版显示时，按返回键取消连接并跳转 SetupActivity
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK &&
            binding.validatingOverlay.visibility == View.VISIBLE) {
            socket?.close()
            startActivity(android.content.Intent(this, SetupActivity::class.java))
            finish()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            hideVocalPanel()
            binding.remoteMenu.visibility = if (binding.remoteMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (binding.remoteMenu.visibility == View.VISIBLE) binding.remotePlay.requestFocus()
            resetMenuTimer()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK && binding.remoteMenu.visibility == View.VISIBLE) {
            binding.remoteMenu.visibility = View.GONE
            return true
        }
        if (binding.remoteMenu.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) resetMenuTimer()
        if (binding.queueOverlay.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            binding.queueOverlay.visibility = View.GONE
            return true
        }
        // 原/伴唱选择栏：BACK 收起；左右键确保焦点在面板内
        if (binding.vocalPanel.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    hideVocalPanel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (binding.btnVocalAccompaniment.hasFocus()) {
                        binding.btnVocalOriginal.requestFocus()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (binding.btnVocalOriginal.hasFocus()) {
                        binding.btnVocalAccompaniment.requestFocus()
                        return true
                    }
                }
            }
            resetVocalTimer()
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            // 待机页按 BACK：双击退出程序
            if (event.keyCode == KeyEvent.KEYCODE_BACK && binding.playerView.visibility != View.VISIBLE) {
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2000) {
                    isFinishing = true
                    finish()
                } else {
                    lastBackAt = now
                    Toast.makeText(this, R.string.back_exit_hint, Toast.LENGTH_SHORT).show()
                }
                return true
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE -> {
                    sendControl("mute", "{\"muted\":${!currentMuted}}")
                    return true
                }
            }
            if (binding.remoteMenu.visibility != View.VISIBLE && binding.queueOverlay.visibility != View.VISIBLE) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        togglePlayback()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        sendControl("next")
                        return true
                    }
                    // 左键直接切歌（按用户习惯，不再呼出队列；队列可从遥控菜单进入）
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        sendControl("next")
                        return true
                    }
                    // 待机页：上键聚焦切换按钮
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        // 不再将焦点导航到切换按钮
                        return true
                    }
                    // 待机页连续按确认键5次：弹出退出确认
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (binding.standbyPanel.visibility == View.VISIBLE) {
                            checkStandbyExitClick(System.currentTimeMillis())
                            return true
                        }
                        showVocalPanel()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- 音量 OSD ----

    private val volumeHide = Runnable { binding.volumeOsd.visibility = View.GONE }

    /** 任何来源的音量变化都调用：顶部显示 2.5s 当前音量与满量程进度。 */
    private fun showVolumeOsd(volume: Int, muted: Boolean) {
        binding.txtVolume.text = if (muted) "已静音" else "音量 $volume"
        binding.volumeBar.progress = if (muted) 0 else volume
        binding.volumeOsd.visibility = View.VISIBLE
        binding.volumeOsd.removeCallbacks(volumeHide)
        binding.volumeOsd.postDelayed(volumeHide, 2_500L)
    }

    // ---- 原唱/伴唱选择栏 ----

    private val vocalHide = Runnable {
        binding.vocalPanel.visibility = View.GONE
        // 弹窗关闭后，焦点归还主视图
        if (binding.standbyPanel.visibility == View.VISIBLE) {
            binding.standbyContent.requestFocus()
        }
    }

    private fun showVocalPanel() {
        updateVocalPanelSelection()
        binding.vocalPanel.visibility = View.VISIBLE
        (if (currentVocalMode == "original") binding.btnVocalOriginal else binding.btnVocalAccompaniment).requestFocus()
        resetVocalTimer()
    }

    private fun hideVocalPanel() {
        binding.vocalPanel.visibility = View.GONE
        // 弹窗关闭后，焦点归还主视图
        if (binding.standbyPanel.visibility == View.VISIBLE) {
            binding.standbyContent.requestFocus()
        }
    }

    private fun resetVocalTimer() {
        binding.vocalPanel.removeCallbacks(vocalHide)
        binding.vocalPanel.postDelayed(vocalHide, 6_000L)
    }

    /** 当前生效模式金色高亮，另一个白色。 */
    private fun updateVocalPanelSelection() {
        val gold = resources.getColor(R.color.gold, null)
        val white = resources.getColor(android.R.color.white, null)
        val original = currentVocalMode == "original"
        binding.btnVocalOriginal.setTextColor(if (original) gold else white)
        binding.btnVocalAccompaniment.setTextColor(if (original) white else gold)
    }

    private fun setupRemoteMenu() {
        binding.remotePlay.setOnClickListener { togglePlayback() }
        binding.remoteNext.setOnClickListener { sendControl("next") }
        binding.remoteRestart.setOnClickListener { sendControl("restart") }
        binding.remoteVocal.setOnClickListener { toggleVocal() }
        binding.remoteVolUp.setOnClickListener { changeVolume(10) }
        binding.remoteVolDown.setOnClickListener { changeVolume(-10) }
        binding.remoteMute.setOnClickListener { sendControl("mute", "{\"muted\":${!currentMuted}}") }
        binding.remoteQueue.setOnClickListener { showQueueOverlay() }
        binding.remoteMicrophone.setOnClickListener { toggleMicrophoneMonitor() }
        binding.logoArea.setOnClickListener { onLogoAreaClicked() }
        updateMicrophoneButton()
        binding.queueClose.setOnClickListener { binding.queueOverlay.visibility = View.GONE }
        binding.btnVocalOriginal.setOnClickListener {
            sendControl("set_vocal", "{\"mode\":\"original\"}")
            hideVocalPanel()
        }
        binding.btnVocalAccompaniment.setOnClickListener {
            sendControl("set_vocal", "{\"mode\":\"accompaniment\"}")
            hideVocalPanel()
        }
        binding.queueNow.isFocusable = true
        binding.queueNow.setOnLongClickListener {
            if (hasCurrentSong) confirmControl("切掉当前歌曲", "确定切换到下一首吗？", "next")
            true
        }
    }

    private fun resetMenuTimer() {
        binding.remoteMenu.removeCallbacks(menuHide)
        binding.remoteMenu.postDelayed(menuHide, 10_000L)
    }

    private fun toggleMicrophoneMonitor() {
        if (microphoneActive) {
            config.microphoneMonitorEnabled = false
            microphoneMonitor?.stop()
            microphoneActive = false
            updateMicrophoneButton()
            onToast("麦克风监听已关闭")
        } else {
            config.microphoneMonitorEnabled = true
            ensureMicrophonePermissionsAndStart()
        }
    }

    private fun ensureMicrophonePermissionsAndStart() {
        val permissions = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            val bluetoothInputPresent = microphoneMonitor?.externalInputs()
                ?.any(com.homektv.tv.player.MicrophoneInputSelector::isBluetoothInput) == true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && bluetoothInputPresent &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (permissions.isEmpty()) startMicrophoneMonitor()
        else microphonePermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startMicrophoneMonitor() {
        if (microphoneMonitor?.start() != true) updateMicrophoneButton()
    }

    private fun updateMicrophoneButton() {
        if (!::binding.isInitialized) return
        binding.remoteMicrophone.text = if (microphoneActive) "麦克风：开" else "麦克风：关"
    }

    private fun applyVideoScaleMode(mode: String) {
        binding.playerView.resizeMode = when (mode) {
            "fit" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
    }

    private var currentVolume = 60
    private var currentMuted = false
    private var currentVocalMode = "accompaniment"

    private fun togglePlayback() {
        sendControl(if (currentPlaybackState == "playing") "pause" else "play")
    }

    /** 原唱 ↔ 伴唱切换（遥控菜单按钮与方向下键共用）。 */
    private fun toggleVocal() {
        sendControl("set_vocal", "{\"mode\":\"${if (currentVocalMode == "original") "accompaniment" else "original"}\"}")
    }

    private fun changeVolume(delta: Int) {
        val volume = (currentVolume + delta).coerceIn(0, 100)
        sendControl("set_volume", "{\"volume\":$volume}")
    }

    private fun showQueueOverlay() {
        binding.remoteMenu.visibility = View.GONE
        binding.queueOverlay.visibility = View.VISIBLE
        val target = binding.queueList.getChildAt(0) ?: binding.queueClose
        target.requestFocus()
    }

    private fun confirmControl(title: String, message: String, action: String, params: String = "{}") {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> sendControl(action, params) }
            .show()
    }

    private fun sendControl(action: String, params: String = "{}") {
        showPlaybackProgress()
        lifecycleScope.launch { if (!mediaApi.control(action, params)) onToast("操作失败") }
    }

    // ---- KtvSocket.Listener ----

    override fun onConnectionChanged(connected: Boolean) {
        if (connected) {
            connectionFailed = false
            blacklistNotified = false
            binding.root.removeCallbacks(connectionTimeoutRunnable)
            binding.statusIndicator.setBackgroundResource(R.drawable.status_indicator_green)
            // WS 连接后立即查询授权状态（pending/approved/blacklisted 都由 HTTP 结果决定）
            // 每次重连都强制检查，因为远程状态可能已变化（如被解散/拒绝）
            runAuthorizeCheck(force = true)
            checkForTvUpdate()
        } else {
            binding.root.removeCallbacks(authorizeRetryRunnable)
            binding.statusIndicator.setBackgroundResource(R.drawable.status_indicator_yellow)
        }
    }

    /**
     * 调用 GET /api/tv/authorize 校验当前设备授权状态。
     *
     * <ul>
     *   <li>approved → 关闭任何授权蒙版，正常显示待机页</li>
     *   <li>pending / blacklisted / room_not_open / idle → 弹授权状态蒙版</li>
     *   <li>network_error → 30s 后自动重试（最多持续直到成功或返回非 network_error）</li>
     * </ul>
     */
    private fun runAuthorizeCheck(force: Boolean = false) {
        if (authorizeCheckInFlight) return
        if (authorizeCompleted && !force) return
        if (!::mediaApi.isInitialized) return
        // 如果已收到 device_idle WS 事件，忽略 HTTP 授权结果（避免闪烁）
        if (deviceIdleReceived) {
            Log.d(TAG, "runAuthorizeCheck: 忽略，device_idle 已收到")
            return
        }
        val deviceId = config.deviceId
        authorizeCheckInFlight = true
        authorizationInFlight = true
        lifecycleScope.launch {
            try {
                val result = mediaApi.authorize(deviceId)
                authorizeCheckInFlight = false
                authorizationInFlight = false
                if (isFinishing) return@launch
                // 双重检查：可能在 HTTP 响应回来前已收到 device_idle
                if (deviceIdleReceived) {
                    Log.d(TAG, "authorize result: 忽略，device_idle 已收到")
                    return@launch
                }
                when (result.status) {
                    "approved" -> {
                        authorizeCompleted = true
                        binding.root.removeCallbacks(authorizeRetryRunnable)
                        hideRoomStatusOverlay()
                        applyRoomName(result.roomName)
                        // HTTP 授权响应中直接带 qr_code，避免等 WS 的 device_approved 事件
                        currentQrCode = result.qrCode
                        loadStandbyDataIfApproved()
                    }
                    "pending" -> {
                        authorizeCompleted = true
                        binding.root.removeCallbacks(authorizeRetryRunnable)
                        showRoomStatusOverlay(
                            R.string.room_status_pending,
                            R.string.room_status_pending_hint,
                            true,
                            parseIsoToMs(result.expiredAt),
                        )
                    }
                    "blacklisted" -> {
                        authorizeCompleted = true
                        standbyLoaded = false
                        binding.root.removeCallbacks(authorizeRetryRunnable)
                        binding.validatingOverlay.tag = "blacklisted"
                        binding.validatingOverlay.visibility = View.VISIBLE
                        binding.txtOverlayTitle.text = getString(R.string.room_status_blacklisted)
                        binding.txtValidatingHost.text = ""
                        clearStandbyData()
                        socket?.close()
                        socket = null
                        binding.root.postDelayed({
                            if (!isFinishing) {
                                startActivity(android.content.Intent(this@MainActivity, SetupActivity::class.java))
                                finish()
                            }
                        }, 3000L)
                    }
                    "room_not_open" -> {
                        authorizeCompleted = true
                        standbyLoaded = false
                        binding.root.removeCallbacks(authorizeRetryRunnable)
                        clearStandbyData()
                        showRoomStatusOverlay(R.string.room_status_not_open, 0, false)
                    }
                    "idle" -> {
                        authorizeCompleted = true
                        standbyLoaded = false
                        binding.root.removeCallbacks(authorizeRetryRunnable)
                        clearStandbyData()
                        showRoomStatusOverlay(R.string.room_status_idle, 0, false)
                    }
                    else -> {
                        binding.root.removeCallbacks(authorizeRetryRunnable)
                        binding.root.postDelayed(authorizeRetryRunnable, 30_000L)
                    }
                }
            } catch (e: Throwable) {
                // 网络异常（如连不上服务器）：清除标志，让 onConnectionFailed 检测到并跳转
                authorizeCheckInFlight = false
                authorizationInFlight = false
                if (!isFinishing) {
                    binding.root.removeCallbacks(authorizeRetryRunnable)
                    binding.root.postDelayed(authorizeRetryRunnable, 30_000L)
                }
            }
        }
    }

    /** 关闭授权状态蒙版并恢复正常待机页。 */
    private fun hideRoomStatusOverlay() {
        if (!::binding.isInitialized) return
        if (binding.validatingOverlay.visibility != View.VISIBLE) return
        binding.root.removeCallbacks(pendingCountdownTick)
        pendingExpiredAtMs = null
        binding.validatingOverlay.tag = null
        binding.validatingOverlay.visibility = View.GONE
        binding.txtOverlayTitle.text = "正在连接..."
    }

    override fun onDeviceApproved(roomName: String, qrCode: String?, activeStart: String?, activeEnd: String?) {
        // 名称为空或 blank → 显示默认标题 HOME KTV
        val displayName = roomName?.takeIf { it.isNotBlank() } ?: getString(R.string.default_room_name)
        currentRoomName = displayName
        currentQrCode = qrCode
        runOnUiThread {
            onToast("房间 $displayName 已批准，连接成功")
            authorizeCompleted = true
            binding.root.removeCallbacks(authorizeRetryRunnable)
            binding.root.removeCallbacks(pendingCountdownTick)
            pendingExpiredAtMs = null
            binding.validatingOverlay.visibility = View.GONE
            binding.txtOverlayTitle.text = "正在连接..."
            binding.txtBrandName.text = displayName
            showStandby()
            loadQr()
            if (!standbyLoaded) loadStandbyDataIfApproved()
        }
    }

    override fun onDevicePending(expiredAt: String?) {
        runOnUiThread {
            authorizeCompleted = true
            binding.root.removeCallbacks(authorizeRetryRunnable)
            clearStandbyData()
            showRoomStatusOverlay(
                R.string.room_status_pending,
                R.string.room_status_pending_hint,
                true,
                parseIsoToMs(expiredAt),
            )
        }
    }

    private var blacklistNotified = false

    override fun onDeviceBlacklisted() {
        runOnUiThread {
            if (blacklistNotified) return@runOnUiThread
            blacklistNotified = true
            binding.root.removeCallbacks(pendingCountdownTick)
            pendingExpiredAtMs = null
            currentQrCode = null
            authorizeCompleted = false
            standbyLoaded = false
            clearStandbyData()
            binding.validatingOverlay.visibility = View.VISIBLE
            binding.txtOverlayTitle.text = getString(R.string.room_status_blacklisted)
            binding.txtValidatingHost.text = ""
            // 3秒后返回连接页面
            binding.root.postDelayed({
                if (!isFinishing) {
                    startActivity(android.content.Intent(this, SetupActivity::class.java))
                    finish()
                }
            }, 3000L)
        }
    }

    override fun onRoomNotOpen() {
        runOnUiThread {
            clearStandbyData()
            showRoomStatusOverlay(R.string.room_status_not_open, 0, false)
        }
    }

    override fun onRoomDisabled() {
        runOnUiThread {
            if (blacklistNotified) return@runOnUiThread
            binding.root.removeCallbacks(pendingCountdownTick)
            pendingExpiredAtMs = null
            currentQrCode = null
            authorizeCompleted = false
            standbyLoaded = false
            clearStandbyData()
            binding.validatingOverlay.visibility = View.VISIBLE
            binding.txtValidatingHost.text = ""
            binding.root.post { runAuthorizeCheck(force = true) }
        }
    }

    override fun onRoomDissolved() {
        runOnUiThread {
            if (blacklistNotified) return@runOnUiThread
            binding.root.removeCallbacks(pendingCountdownTick)
            pendingExpiredAtMs = null
            currentQrCode = null
            authorizeCompleted = false
            standbyLoaded = false
            clearStandbyData()
            binding.validatingOverlay.visibility = View.VISIBLE
            binding.txtOverlayTitle.text = getString(R.string.room_status_idle)
            binding.txtValidatingHost.text = ""
            binding.root.post { runAuthorizeCheck(force = true) }
        }
    }

    override fun onDeviceIdle() {
        deviceIdleReceived = true
        runOnUiThread {
            if (blacklistNotified) return@runOnUiThread
            binding.root.removeCallbacks(pendingCountdownTick)
            pendingExpiredAtMs = null
            currentQrCode = null
            authorizeCompleted = false
            standbyLoaded = false
            clearStandbyData()
            binding.validatingOverlay.visibility = View.VISIBLE
            binding.txtOverlayTitle.text = getString(R.string.room_status_idle)
            binding.txtValidatingHost.text = ""
            binding.root.post { runAuthorizeCheck(force = true) }
        }
    }

    override fun onRoomNameChanged(name: String) {
        applyRoomName(name)
        runOnUiThread { onToast("房间名称已更新为: $currentRoomName") }
    }

    /**
     * 统一的名字更新逻辑：
     * - 名称为空或 blank → 显示默认标题 HOME KTV
     * - 有名称 → 显示该名称
     * 同步更新 currentRoomName 和左上角 txtBrandName。
     */
    private fun applyRoomName(name: String?) {
        val displayName = name?.takeIf { it.isNotBlank() } ?: getString(R.string.default_room_name)
        currentRoomName = displayName
        binding.txtBrandName.text = displayName
    }

    override fun onRoomTimeChanged(activeStart: String?, activeEnd: String?, qrCode: String?) {
        currentQrCode = qrCode
        runOnUiThread {
            onToast("房间开放时间已更新")
            // 重新加载二维码
            loadQr()
        }
    }

    override fun onQrCodeRefreshed(qrCode: String) {
        currentQrCode = qrCode
        runOnUiThread {
            onToast("二维码已刷新")
            loadQr()
        }
    }

    override fun onMemberJoined(deviceId: String, nickname: String, memberCount: Long) {
        runOnUiThread {
            binding.txtPhones?.text = if (memberCount > 0) "房间 ${memberCount} 人" else "房间 0 人"
            onToast("$nickname 加入了房间")
        }
    }

    override fun onApplicationExpired() {
        runOnUiThread {
            showRoomStatusOverlay(R.string.room_status_expired, 0, false)
            socket?.close()
            socket = null
        }
    }

    /** 当前房间信息 */
    private var currentRoomName: String? = ""
    private var currentQrCode: String? = null

    /** 显示房间状态蒙版（等待审批/黑名单/房间关闭等）。 */
    private fun showRoomStatusOverlay(titleRes: Int, hintRes: Int, allowReconnect: Boolean, expiredAtMs: Long? = null) {
        binding.validatingOverlay.visibility = View.VISIBLE
        binding.validatingOverlay.tag = "room_status"
        binding.txtOverlayTitle.text = getString(titleRes)
        binding.txtValidatingHost.text = if (hintRes != 0) getString(hintRes) else ""

        pendingExpiredAtMs = expiredAtMs
        binding.root.removeCallbacks(pendingCountdownTick)
        if (expiredAtMs != null) {
            updatePendingCountdown()
            binding.root.post(pendingCountdownTick)
        }
    }

    /** 刷新 pending 倒计时：合并到标题中居中显示。 */
    private fun updatePendingCountdown() {
        val expiredAtMs = pendingExpiredAtMs ?: return
        val remainingMs = (expiredAtMs - System.currentTimeMillis()).coerceAtLeast(0)
        if (remainingMs <= 0) {
            binding.txtOverlayTitle.text = getString(R.string.room_status_pending)
            binding.root.removeCallbacks(pendingCountdownTick)
            return
        }
        val totalSec = remainingMs / 1000
        val mm = (totalSec / 60).toInt()
        val ss = (totalSec % 60).toInt()
        val countdown = String.format(Locale.getDefault(), "%02d:%02d", mm, ss)
        binding.txtOverlayTitle.text = "${getString(R.string.room_status_pending)} ($countdown)"
        binding.root.postDelayed(pendingCountdownTick, 1000L)
    }

    /**
     * 解析后端 ISO 字符串 → 毫秒时间戳。
     * 支持格式：
     *   - 新格式（推荐）：2026-08-19T10:52:00Z（含 Z 后缀，UTC）
     *   - ISO offset 格式：2026-08-19T19:15:00+08:00（Java SimpleDateFormat 不识别冒号，需替换）
     *   - 旧格式（兼容）：2026-08-19T10:52:00（无时区，按本地时区解析，有偏差但能显示）
     * 解析失败返回 null，倒计时不显示。
     */
    private fun parseIsoToMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            // 统一去掉末尾的 Z 后缀，再用 OffsetDateTime 解析（自动识别 +08:00 / +0000）
            val withoutZ = iso.trimEnd('Z')
            OffsetDateTime.parse(withoutZ)?.toInstant()?.toEpochMilli()
        } catch (_: Exception) {
            // 旧格式（无时区）：按本地时区解析
            try {
                val pattern = if (iso.contains(".")) "yyyy-MM-dd'T'HH:mm:ss.SSS" else "yyyy-MM-dd'T'HH:mm:ss"
                SimpleDateFormat(pattern, Locale.US).parse(iso)?.time
            } catch (_: Exception) { null }
        }
    }

    private fun onLogoAreaClicked() {
        if (binding.standbyPanel.visibility != View.VISIBLE) return
        checkStandbyExitClick(System.currentTimeMillis())
    }

    private fun checkStandbyExitClick(now: Long) {
        if (now - lastStandbyClickTime > standbyClickWindowMs) {
            consecutiveStandbyClicks = 1
        } else {
            consecutiveStandbyClicks++
        }
        lastStandbyClickTime = now
        if (consecutiveStandbyClicks >= standbyClickThreshold) {
            consecutiveStandbyClicks = 0
            exitServer()
        }
    }

    private fun exitServer() {
        AlertDialog.Builder(this)
            .setCancelable(false)
            .setTitle("退出服务器")
            .setMessage("确定退出当前服务器吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                socket?.close()
                // 不清除 serverHost，下次打开会自动连接
                startActivity(Intent(this, SetupActivity::class.java))
                finish()
            }
            .show()
    }

    override fun onConnectionFailed() {
        if (isFinishing) return
        // 防抖：如果正在显示失败蒙版，不再重复触发
        if (binding.validatingOverlay.tag == "failed") return
        binding.validatingOverlay.tag = "failed"
        binding.statusIndicator.setBackgroundResource(R.drawable.status_indicator_red)

        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            // 如果授权 HTTP 请求已在路上，等它返回（可能会覆盖成黑名单等正确状态），
            // 不在这里提前显示"连接失败"误导用户
            if (authorizationInFlight) return@runOnUiThread

            connectionFailed = true
            binding.validatingOverlay.visibility = View.VISIBLE
            binding.txtOverlayTitle.text = "您的设备被限制接入，请联系管理员"
            // 显示 1.5s 后跳转
            binding.validatingOverlay.postDelayed({
                if (!isFinishing && connectionFailed) {
                    Toast.makeText(this, "您的设备被限制接入，请联系管理员", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, SetupActivity::class.java))
                    finish()
                }
            }, 1500L)
        }
    }

    private fun checkForTvUpdate() {
        if (releaseCheckInFlight) return
        releaseCheckInFlight = true
        lifecycleScope.launch {
            val release = mediaApi.fetchReleaseInfo()
            releaseCheckInFlight = false
            if (release == null || release.version.isBlank() || release.versionCode <= 0) return@launch
            val releaseKey = "${release.versionCode}:${release.version}"
            if (checkedReleaseVersion == releaseKey) return@launch
            checkedReleaseVersion = releaseKey
            if (release.versionCode == BuildConfig.VERSION_CODE.toLong() || promptedReleaseVersion == releaseKey) return@launch

            val apk = when {
                Build.SUPPORTED_ABIS.contains("arm64-v8a") -> release.tv.arm64V8a
                Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> release.tv.armeabiV7a
                else -> null
            }
            // TODO: 暂时注释掉版本不匹配的提示
            // if (apk == null || !apk.available || apk.url.isBlank()) {
            //     onToast("服务端版本为 ${release.version}，但没有适配本机架构的安装包")
            //     return@launch
            // }
            if (apk == null || !apk.available || apk.url.isBlank()) return@launch
            promptedReleaseVersion = releaseKey
            showUpdateDialog(release.version, apk)
        }
    }

    private fun showUpdateDialog(version: String, apk: ApkPackageInfo) {
        val size = if (apk.size > 0) " · %.1f MB".format(Locale.US, apk.size / 1024.0 / 1024.0) else ""
        AlertDialog.Builder(this)
            .setTitle("发现 Android TV 新版本")
            .setMessage("当前版本 ${BuildConfig.VERSION_NAME}\n服务端版本 $version\n安装包 ${apk.abi}$size")
            .setNegativeButton("暂不更新", null)
            .setPositiveButton("去下载") { _, _ -> downloadAndInstallUpdate(version, apk) }
            .show()
    }

    private fun downloadAndInstallUpdate(version: String, apk: ApkPackageInfo) {
        val progress = AlertDialog.Builder(this)
            .setTitle("正在下载 $version")
            .setMessage("安装包下载完成后将打开系统安装界面。")
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            val destination = File(cacheDir, "updates/home-ktv-tv-${apk.abi}.apk")
            val downloaded = mediaApi.downloadApk(apk.url, destination, apk.size)
            progress.dismiss()
            if (!downloaded) {
                checkedReleaseVersion = null
                promptedReleaseVersion = null
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("安装包下载失败")
                    .setMessage("请检查服务端连接后重试。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("重试") { _, _ -> downloadAndInstallUpdate(version, apk) }
                    .show()
                return@launch
            }
            pendingUpdateApk = destination
            requestInstallOrOpen(destination)
        }
    }

    private fun requestInstallOrOpen(apk: File) {
        if (packageManager.canRequestPackageInstalls()) {
            installDownloadedApk(apk)
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName"),
        )
        runCatching { unknownSourcesLauncher.launch(intent) }
            .onFailure { onToast("当前电视无法打开未知来源安装设置") }
    }

    private fun installDownloadedApk(apk: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { onToast("无法打开系统安装程序") }
    }

    private var snapshotReceived = false

    override fun onSnapshot(event: String, snapshot: QueueSnapshot) {
        // 音量/静音变化（遥控音量键、H5、遥控菜单任何来源）→ 顶部 OSD；首个快照不弹
        if (snapshotReceived && (snapshot.volume != currentVolume || snapshot.muted != currentMuted)) {
            showVolumeOsd(snapshot.volume, snapshot.muted)
        }
        snapshotReceived = true
        currentVolume = snapshot.volume
        currentMuted = snapshot.muted
        currentVocalMode = snapshot.vocalMode
        if (binding.vocalPanel.visibility == View.VISIBLE) updateVocalPanelSelection()
        currentPlaybackState = snapshot.state
        val lyricsPlaying = snapshot.state == "playing"
        binding.txtLyricPrevious.updatePlayback(engine?.currentPositionMs ?: 0L, lyricsPlaying)
        binding.txtAudioLyricCurrent.updatePlayback(engine?.currentPositionMs ?: 0L, lyricsPlaying)
        hasCurrentSong = snapshot.playing != null
        renderQueue(snapshot)
        binding.txtPhones.text =
            getString(R.string.status_phones, snapshot.connectedPhones.toInt())
        applyPlayback(snapshot)
        if (event == PLAYBACK_RESTARTED_EVENT) {
            engine?.restart()
            lastLyricIndex = -1
            updateProgress(0L)
        }
        if (event == VOCAL_CHANGED_EVENT) refreshVocalTrackMapping(snapshot)
    }

    private fun renderQueue(snapshot: QueueSnapshot) {
        val now = snapshot.playing?.song
        binding.queueNow.text = if (now == null) "当前演唱：暂无" else "正在演唱  ${now.title} · ${now.artist}"
        binding.queueList.removeAllViews()
        snapshot.list.forEachIndexed { index, item ->
            val song = item.song ?: return@forEachIndexed
            val row = TextView(this).apply {
                text = "%02d    %s · %s    %s".format(index + 1, song.title, song.artist, item.orderedByNick ?: "")
                textSize = 20f
                setTextColor(getColor(R.color.dim))
                setPadding(18, 20, 18, 20)
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    item.queueId?.let { sendControl("top", "{\"queue_id\":$it}") }
                }
                setOnLongClickListener {
                    item.queueId?.let {
                        confirmControl(
                            "删除待播歌曲",
                            "确定从队列删除《${song.title}》吗？",
                            "cancel",
                            "{\"queue_id\":$it}",
                        )
                    }
                    true
                }
            }
            binding.queueList.addView(row)
        }
        if (binding.queueOverlay.visibility == View.VISIBLE && binding.queueOverlay.findFocus() == null) {
            (binding.queueList.getChildAt(0) ?: binding.queueClose).requestFocus()
        }
    }

    override fun onToast(text: String) {
        if (text.isNotBlank()) Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onEffect(effectId: String) {
        effectPlayer?.play(effectId, currentVolume, currentMuted)
        effectOverlay?.play(effectId)
    }

    // ---- 播放驱动（P1.28） ----

    private fun applyPlayback(snapshot: QueueSnapshot) {
        val playing = snapshot.playing
        val songId = playing?.song?.id

        // idle 或无当前曲目：停止、回待机页
        if (snapshot.state == "idle" || playing == null || songId == null) {
            currentQueueId = null
            engine?.stop()
            binding.txtLyricPrevious.stopAnimation()
            binding.txtAudioLyricCurrent.stopAnimation()
            showStandby()
            return
        }

        updatePlayerInfo(snapshot)

        val eng = engine ?: return
        val volume = snapshot.volume
        val muted = snapshot.muted

        // 同一首：只处理播放/暂停 + 音量，不重新装载
        if (playing.queueId == currentQueueId) {
            eng.applyVolume(volume, muted)
            eng.setVocalMode(snapshot.vocalMode, accompanimentTrackIndex, audioTrackCount)
            if (snapshot.state == "paused") eng.pause() else eng.resume()
            return
        }

        // 换歌：拉详情取文件源 → 播放
        currentQueueId = playing.queueId
        val targetQueueId = playing.queueId
        val audioMode = playing.song?.mediaType.equals("AUDIO", ignoreCase = true)
        lyricLines = emptyList()
        lastLyricIndex = -1
        binding.txtLyricPrevious.animate().cancel()
        binding.txtLyricCurrent.animate().cancel()
        binding.txtLyricPrevious.alpha = 1f
        binding.txtLyricCurrent.alpha = 1f
        binding.txtLyricPrevious.stopAnimation()
        binding.txtLyricCurrent.text = ""
        binding.txtAudioLyricCurrent.stopAnimation()
        binding.txtAudioLyricNext.text = ""
        showPlayer(audioMode)
        if (audioMode) {
            val song = playing.song
            binding.imgAudioCover.setImageDrawable(null)
            binding.txtAudioFallback.text = song?.title.orEmpty().take(2).ifBlank { "KTV" }
            binding.txtAudioTitle.text = song?.title.orEmpty()
            binding.txtAudioArtist.text = song?.artist.orEmpty()
            binding.txtAudioLyricCurrent.setLine(null, 0L)
            binding.txtAudioLyricNext.text = song?.title.orEmpty()
        }
        lifecycleScope.launch {
            val file = mediaApi.bestFileSource(songId)
            if (currentQueueId != targetQueueId) return@launch
            if (file == null) {
                onPlayError()
                return@launch
            }
            accompanimentTrackIndex = file.vocalTrackIndex
            audioTrackCount = file.audioTracks
            currentFileId = file.id
            lyricLines = mediaApi.fetchLyric(songId)?.let(LrcParser::parse).orEmpty()
            if (currentQueueId != targetQueueId) return@launch
            if (audioMode) {
                mediaApi.fetchCover(songId)?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { binding.imgAudioCover.setImageBitmap(it) }
                }
                if (lyricLines.isNotEmpty()) binding.txtAudioLyricNext.text = lyricLines.first().text
            }
            eng.applyVolume(volume, muted)
            eng.play(file.id, mediaApi.streamUrl(file.id))
            eng.setVocalMode(snapshot.vocalMode, accompanimentTrackIndex, audioTrackCount)
            if (snapshot.state == "paused") eng.pause()
        }
    }

    private fun refreshVocalTrackMapping(snapshot: QueueSnapshot) {
        val playing = snapshot.playing ?: return
        val songId = playing.song?.id ?: return
        val targetQueueId = playing.queueId
        lifecycleScope.launch {
            val file = mediaApi.bestFileSource(songId) ?: return@launch
            if (currentQueueId != targetQueueId || currentFileId != file.id) return@launch
            accompanimentTrackIndex = file.vocalTrackIndex
            audioTrackCount = file.audioTracks
            engine?.setVocalMode(snapshot.vocalMode, accompanimentTrackIndex, audioTrackCount)
        }
    }

    private fun startStandbyMotion() {
        binding.qrPanel.post {
            // 使用硬件层加速，避免每帧重绘导致的卡顿
            binding.qrPanel.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            ObjectAnimator.ofFloat(binding.qrPanel, View.SCALE_X, 1f, 1.012f).apply {
                duration = 1_800L
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                standbyMotionAnimators += this
                start()
            }
            ObjectAnimator.ofFloat(binding.qrPanel, View.SCALE_Y, 1f, 1.012f).apply {
                duration = 1_800L
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                standbyMotionAnimators += this
                start()
            }
        }
    }

    // ---- 待机页二维码（P1.30） ----

    /**
     * 清除待机页数据：进入 idle/blacklisted 等非授权状态时调用。
     * 避免透过蒙版仍看到旧二维码、歌曲数、推荐歌曲等。
     */
    private fun clearStandbyData() {
        binding.imgQr.setImageDrawable(null)
        binding.imgMiniQr.setImageDrawable(null)
        binding.imgAudioMiniQr.setImageDrawable(null)
        binding.imgQr.visibility = View.GONE
        binding.txtQrPlaceholder.visibility = View.VISIBLE
        binding.txtLibraryStat.text = "曲库 - 首"
        recommendations = emptyList()
        binding.recommendationRow.visibility = View.GONE
        binding.txtRecommendationsEmpty.visibility = View.VISIBLE
        binding.standbyPanel.removeCallbacks(standbyTicker)
        binding.standbyPanel.removeCallbacks(standbySettingsTicker)
        standbyMotionAnimators.forEach(ObjectAnimator::cancel)
        standbyMotionAnimators.clear()
        binding.txtStandbyWelcome.text = ""
        binding.txtStandbySubtitle.text = ""
        binding.imgStandbyLogo.setImageDrawable(null)
        binding.txtBrandName.text = getString(R.string.default_room_name)
    }

    /**
     * 仅在授权通过后加载待机页数据（QR / standbyContent / 曲库数 / 推荐歌单）。
     * pending/blacklisted/room_not_open/idle 期间不会触发任何房间信息接口。
     */
    private fun loadStandbyDataIfApproved() {
        if (!authorizeCompleted || standbyLoaded) return
        standbyLoaded = true
        loadQr()
        loadStandbyContent()
        startStandbyMotion()
        binding.standbyPanel.post(standbyTicker)
        binding.standbyPanel.post(standbySettingsTicker)
    }

    /** 异步拉 /api/qr 加载进待机页占位框；失败保留占位文字（用户仍可读明文地址）。 */
    private fun loadQr() {
        lifecycleScope.launch {
            val bytes = mediaApi.fetchQr(QR_SIZE_PX, currentQrCode)
            val bmp = bytes?.let {
                runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
            }
            if (bmp != null) {
                binding.imgQr.setImageBitmap(bmp)
                binding.imgMiniQr.setImageBitmap(bmp)
                binding.imgAudioMiniQr.setImageBitmap(bmp)
                binding.imgQr.visibility = View.VISIBLE
                binding.txtQrPlaceholder.visibility = View.GONE
            }
            // 失败时保持 "二维码加载中…" 占位文字可见（fetchQr 已记 log）。
        }
    }

    private fun loadStandbyContent() {
        lifecycleScope.launch {
            val content = mediaApi.fetchStandbyContent()
            applyStandbyContent(content)
            val libraryCount = mediaApi.fetchLibraryCount()
            binding.txtLibraryStat.text = "曲库 ${libraryCount ?: recommendations.size} 首"
            binding.recommendationRow.visibility = if (recommendations.isEmpty()) View.GONE else View.VISIBLE
            binding.txtRecommendationsEmpty.visibility = if (recommendations.isEmpty()) View.VISIBLE else View.GONE
            if (recommendations.isNotEmpty()) renderRecommendationCards()
        }
    }

    private fun applyStandbyContent(content: StandbyContent) {
        applyVideoScaleMode(content.videoScaleMode)
        standbyCarouselEnabled = content.carouselEnabled
        antiBurnEnabled = content.antiBurn
        standbyIntervalMs = content.intervalSeconds.coerceIn(3, 60) * 1_000L
        binding.txtStandbyWelcome.text = content.welcomeText
        binding.txtStandbySubtitle.text = content.subtitle
        recommendations = content.songs.sortedByDescending { it.coverUrl != null }
        binding.recommendationRow.visibility = if (recommendations.isEmpty()) View.GONE else View.VISIBLE
        binding.txtRecommendationsEmpty.visibility = if (recommendations.isEmpty()) View.VISIBLE else View.GONE
        if (recommendations.isNotEmpty()) renderRecommendationCards()
        if (content.logoUrl == null) {
            binding.imgStandbyLogo.setImageResource(R.drawable.home_ktv_logo)
            binding.imgStandbyLogo.visibility = View.VISIBLE
            binding.txtBrandName.visibility = View.VISIBLE
        } else {
            lifecycleScope.launch {
                val bitmap = mediaApi.fetchUrl(content.logoUrl)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bitmap != null) {
                    binding.imgStandbyLogo.setImageBitmap(bitmap)
                    binding.imgStandbyLogo.visibility = View.VISIBLE
                    binding.txtBrandName.visibility = View.GONE
                }
            }
        }
        if (!antiBurnEnabled) {
            binding.standbyPanel.translationX = 0f
            binding.standbyPanel.translationY = 0f
        }
    }

    private fun renderRecommendationCards() {
        val visible = (0 until minOf(4, recommendations.size))
            .map { recommendations[(recommendationOffset + it) % recommendations.size] }
        binding.recommendationRow.removeAllViews()
        visible.forEach { song ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(5), dp(3), dp(6), dp(3))
                setBackgroundResource(R.drawable.recommendation_card)
            }
            val params = LinearLayout.LayoutParams(0, dp(82), 1f).apply { marginEnd = dp(8) }
            binding.recommendationRow.addView(card, params)

            val cover = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(getColor(R.color.panel))
                setImageResource(R.drawable.home_ktv_logo)
                recommendationCovers[song.id]?.let(::setImageBitmap)
            }
            card.addView(cover, LinearLayout.LayoutParams(dp(34), dp(34)))

            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), 0, 0, 0)
            }
            card.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            labels.addView(TextView(this).apply {
                text = song.title
                setTextColor(getColor(android.R.color.white))
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            labels.addView(TextView(this).apply {
                text = song.artist
                setTextColor(getColor(R.color.dim))
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })

            if (song.coverUrl != null && !recommendationCovers.containsKey(song.id)) {
                lifecycleScope.launch {
                    val bitmap = mediaApi.fetchCover(song.id)?.let {
                        BitmapFactory.decodeByteArray(it, 0, it.size)
                    }
                    recommendationCovers[song.id] = bitmap
                    if (bitmap != null && song in visible) cover.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showPlayer(audioMode: Boolean = false) {
        binding.playerView.visibility = View.VISIBLE
        binding.standbyPanel.visibility = View.GONE
        binding.ktvOverlay.visibility = if (audioMode) View.GONE else View.VISIBLE
        binding.audioOverlay.visibility = if (audioMode) View.VISIBLE else View.GONE
        showPlaybackProgress()
    }

    private fun showStandby() {
        binding.playerView.visibility = View.GONE
        binding.standbyPanel.visibility = View.VISIBLE
        binding.ktvOverlay.visibility = View.GONE
        binding.audioOverlay.visibility = View.GONE
        hidePlaybackProgress()
    }

    private fun showPlaybackProgress() {
        if (!::binding.isInitialized || binding.playerView.visibility != View.VISIBLE) return
        binding.playerInfoPanel.visibility = View.VISIBLE
        binding.playProgress.visibility = View.VISIBLE
        binding.audioProgress.visibility = View.VISIBLE
        binding.txtElapsed.visibility = View.VISIBLE
        binding.txtVocalMode.visibility = View.VISIBLE
        binding.txtDuration.visibility = View.VISIBLE
        if (lyricLines.isEmpty()) {
            binding.txtLyricPrevious.visibility = View.VISIBLE
            binding.txtLyricCurrent.visibility = View.VISIBLE
        }
        clock.removeCallbacks(progressHide)
        clock.postDelayed(progressHide, PROGRESS_HIDE_DELAY_MS)
    }

    private fun hidePlaybackProgress() {
        if (!::binding.isInitialized) return
        binding.playerInfoPanel.visibility = View.GONE
        binding.playProgress.visibility = View.GONE
        val audioMode = binding.audioOverlay.visibility == View.VISIBLE
        binding.audioProgress.visibility = if (audioMode) View.VISIBLE else View.GONE
        binding.txtAudioElapsed.visibility = if (audioMode) View.VISIBLE else View.GONE
        binding.txtAudioDuration.visibility = if (audioMode) View.VISIBLE else View.GONE
        binding.txtElapsed.visibility = View.GONE
        binding.txtVocalMode.visibility = View.GONE
        binding.txtDuration.visibility = View.GONE
        if (lyricLines.isEmpty()) {
            binding.txtLyricPrevious.visibility = View.GONE
            binding.txtLyricCurrent.visibility = View.GONE
        }
    }

    private fun updatePlayerInfo(snapshot: QueueSnapshot) {
        val current = snapshot.playing ?: return
        val song = current.song ?: return
        binding.txtMediaBadge.text = if (song.mediaType == "KTV_VIDEO") "KTV版" else "MV"
        binding.txtPlayerTitle.text = song.title
        binding.txtPlayerArtist.text = song.artist
        binding.txtOrderedBy.text = current.orderedByNick?.let { "$it 点" } ?: ""
        val next = snapshot.list.firstOrNull { it.status == "waiting" }
        binding.nextPanel.visibility = if (next?.song != null) View.VISIBLE else View.GONE
        binding.txtNextSong.text = next?.song?.let { "${it.title} · ${next.orderedByNick ?: ""}" } ?: ""
        binding.txtAudioNext.text = next?.song?.let { "接下来  ${it.title} · ${it.artist}" } ?: ""
        // lyric timeline is delivered separately in the next lyric task; use the title as a temporary fallback.
        if (lyricLines.isEmpty()) {
            binding.txtLyricCurrent.text = song.title
            binding.txtLyricPrevious.setLine(null, 0L)
        }
        binding.txtVocalMode.text = if (song.hasVocalTrack) {
            if (snapshot.vocalMode == "original") "原唱中" else "伴唱中"
        } else ""
        binding.txtDuration.text = formatMs(song.durationMs.toLong())
    }

    private fun updateProgress(positionMs: Long) {
        val duration = engine?.durationMs ?: 0L
        binding.playProgress.progress = if (duration > 0) ((positionMs * 1000) / duration).toInt().coerceIn(0, 1000) else 0
        binding.audioProgress.progress = binding.playProgress.progress
        binding.txtAudioElapsed.text = formatMs(positionMs)
        binding.txtAudioDuration.text = formatMs(duration)
        binding.txtElapsed.text = formatMs(positionMs)
        if (lyricLines.isNotEmpty()) {
            binding.txtLyricPrevious.visibility = View.VISIBLE
            binding.txtLyricCurrent.visibility = View.VISIBLE
            val index = lyricLines.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
            if (index != lastLyricIndex) {
                lastLyricIndex = index
                binding.txtLyricPrevious.animate().cancel()
                binding.txtLyricCurrent.animate().cancel()
                binding.txtLyricPrevious.alpha = 0.55f
                binding.txtLyricCurrent.alpha = 0.35f
                binding.txtLyricPrevious.animate().alpha(1f).setDuration(180L).start()
                binding.txtLyricCurrent.animate().alpha(1f).setDuration(240L).start()
            }
            // 常规点歌机布局：当前演唱行在上，下一行预告在下。
            val lineEndMs = lyricLines.getOrNull(index + 1)?.startMs
                ?: duration.takeIf { it > lyricLines[index].startMs }
                ?: lyricLines[index].startMs + 5_000L
            binding.txtLyricPrevious.setLine(lyricLines[index], lineEndMs)
            binding.txtLyricPrevious.updatePlayback(positionMs, currentPlaybackState == "playing")
            binding.txtLyricCurrent.text = lyricLines.getOrNull(index + 1)?.text.orEmpty()
            if (binding.audioOverlay.visibility == View.VISIBLE) {
                binding.txtAudioLyricCurrent.setLine(lyricLines[index], lineEndMs)
                binding.txtAudioLyricCurrent.updatePlayback(positionMs, currentPlaybackState == "playing")
                binding.txtAudioLyricNext.text = lyricLines.getOrNull(index + 1)?.text.orEmpty()
            }
        }
    }

    private fun formatMs(ms: Long): String {
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    /** 播放失败：上报文件源，服务端标记失效并推进队列。 */
    private fun onPlayError() {
        Toast.makeText(this, R.string.play_error, Toast.LENGTH_SHORT).show()
        socket?.sendPlayError("media playback failed", currentFileId)
    }

    companion object {
        /** 二维码请求边长（服务端在 [120,1080] 内钳制）；待机占位框 220dp，取 540 兼顾高密度电视清晰度。 */
        private const val QR_SIZE_PX = 540
        private const val PROGRESS_HIDE_DELAY_MS = 5_000L
        private const val VOCAL_CHANGED_EVENT = "vocal_changed"
        private const val PLAYBACK_RESTARTED_EVENT = "playback_restarted"
    }
}
