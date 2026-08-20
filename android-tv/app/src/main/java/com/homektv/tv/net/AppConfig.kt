package com.homektv.tv.net

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * NAS 服务端地址与历史连接持久化。
 *
 * Persists the NAS server address and the history of successful connections.
 */
class AppConfig(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs = appContext
        .getSharedPreferences("ktv_tv", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        migrateLegacyServer()
    }

    /**
     * 形如 192.168.1.10:8080 的服务端 host:port（已归一化）。未配置时为 null。
     * 现在支持存储 https:// 前缀。
     */
    var serverHost: String?
        get() = prefs.getString(KEY_HOST, null)
        set(value) = prefs.edit { putString(KEY_HOST, value) }

    fun clearServerHost() {
        prefs.edit { remove(KEY_HOST) }
    }

    /** 当前配置的协议是否为 HTTPS。 */
    val isHttps: Boolean
        get() = serverHost?.startsWith("https://", ignoreCase = true) == true

    /** 获取不含协议头的主机:端口（用于显示和历史记录）。 */
    fun hostPort(): String = serverHost?.removePrefix("https://")?.removePrefix("http://") ?: ""

    val isConfigured: Boolean get() = !serverHost.isNullOrBlank()

    val savedServers: List<SavedServer>
        get() = readSavedServers()

    /**
     * 连接成功后去重置顶，最多保留 10 台设备。
     *
     * Deduplicates and promotes a successful connection, keeping at most 10 devices.
     * @param fullHost 带协议的完整地址（如 https://example.com:8080）
     */
    @Synchronized
    fun rememberServer(fullHost: String, displayName: String = "") {
        // normalizeHost 会保留协议前缀
        val hostPort = normalizeHost(fullHost) ?: return
        val existing = readSavedServers().firstOrNull { it.hostPort == hostPort }
        val name = when {
            displayName.isNotEmpty() && displayName != hostPort -> displayName
            existing != null -> existing.name
            else -> hostPort  // 直接显示带协议的地址
        }
        val updated = buildList {
            add(SavedServer(hostPort, name))  // 存带协议的地址到历史记录
            addAll(readSavedServers().filterNot { it.hostPort == hostPort })
        }.take(MAX_SAVED_SERVERS)
        writeSavedServers(updated)
        serverHost = hostPort
    }

    /**
     * 兼容旧调用：传入 SavedServer。
     */
    @Synchronized
    fun rememberServer(server: SavedServer) {
        rememberServer(server.hostPort, server.name)
    }

    @Synchronized
    fun removeSavedServer(hostPort: String) {
        writeSavedServers(readSavedServers().filterNot { it.hostPort == hostPort })
    }

    var microphoneMonitorEnabled: Boolean
        get() = prefs.getBoolean(KEY_MICROPHONE_MONITOR, true)
        set(value) = prefs.edit { putBoolean(KEY_MICROPHONE_MONITOR, value) }

    /** WebSocket 地址：ws(s)://host:port/ws?client_type=tv&client_token=xxx&device_id=yyy */
    fun wsUrl(clientToken: String, deviceId: String): String {
        val protocol = if (isHttps) "wss" else "ws"
        return "$protocol://${hostPort()}/ws?client_type=tv&client_token=$clientToken&device_id=$deviceId"
    }

    /** REST/资源基址：htp(s)://host:port/api */
    fun apiBase(): String {
        val protocol = if (isHttps) "https" else "http"
        return "$protocol://${hostPort()}/api"
    }

    /** H5 点歌地址（用于待机页二维码/明文兜底） */
    fun h5Url(): String {
        val protocol = if (isHttps) "https" else "http"
        return "$protocol://${hostPort()}/m"
    }

    /** 稳定的设备 token（首次生成后固定），用于 WS client_token。 */
    val clientToken: String
        get() = prefs.getString(KEY_TOKEN, null) ?: run {
            val t = "tv-" + java.util.UUID.randomUUID().toString().take(8)
            prefs.edit { putString(KEY_TOKEN, t) }
            t
        }

    /** 设备唯一标识（基于 Android ID + 安装时间，用于房间识别）。 */
    val deviceId: String
        get() {
            val stored = prefs.getString(KEY_DEVICE_ID, null)
            if (stored != null && !isKnownBadDeviceId(stored)) {
                return stored
            }
            // 旧值是坏格式 → 重新生成；第一次启动也走这里
            val raw = android.provider.Settings.Secure.getString(
                appContext.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            ).orEmpty()
            val isValidHex = raw.length in 8..32 && raw.matches(Regex("^[0-9a-fA-F]+$"))
            val androidId = if (isValidHex) raw else java.util.UUID.randomUUID().toString().take(16)
            // 不用 "tv-" 前缀，数据库字段只存纯 hex，避免迁移陷阱
            val id = androidId
            android.util.Log.i("AppConfig", "Generated deviceId=$id (raw='$raw', validHex=$isValidHex, previousStored='$stored')")
            prefs.edit { putString(KEY_DEVICE_ID, id) }
            return id
        }

    /** 过滤历史上被坑出来的 deviceId，避免反复撞 unique。 */
    private fun isKnownBadDeviceId(id: String): Boolean {
        // "android_id" 字面字符串（Settings.Secure.ANDROID_ID 在某些 ROM 上就是这个字面值）
        // 或 tv- 前缀的老格式
        if (id == "android_id" || id == "tv-android_id") return true
        // tv- 开头的是旧格式，需要迁移
        if (id.startsWith("tv-")) return true
        // 纯非 hex 内容直接排除
        if (!id.matches(Regex("^[0-9a-fA-F]+$")) && !id.matches(Regex("^[0-9a-f]+$"))) return true
        return false
    }

    private fun migrateLegacyServer() {
        if (prefs.contains(KEY_SAVED_SERVERS)) return
        val legacyHost = prefs.getString(KEY_HOST, null)
        val initial = legacyHost?.takeIf { it.isNotBlank() }
            ?.let { listOf(SavedServer(it, it)) }
            .orEmpty()
        writeSavedServers(initial)
    }

    private fun readSavedServers(): List<SavedServer> {
        val raw = prefs.getString(KEY_SAVED_SERVERS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(SavedServer.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun writeSavedServers(servers: List<SavedServer>) {
        val raw = json.encodeToString(ListSerializer(SavedServer.serializer()), servers)
        prefs.edit { putString(KEY_SAVED_SERVERS, raw) }
    }

    companion object {
        private const val KEY_HOST = "server_host"
        private const val KEY_TOKEN = "client_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_MICROPHONE_MONITOR = "microphone_monitor_enabled"
        private const val KEY_SAVED_SERVERS = "saved_servers"
        private const val MAX_SAVED_SERVERS = 10

        /**
         * 归一化用户输入：去空格、剥离 http(s):// 前缀与尾部斜杠。
         * 如果用户没有输入端口，保持原样（让配置的端口生效）。
         * 保留协议头用于 HTTPS/WSS 支持。
         */
        fun normalizeHost(raw: String): String? {
            var s = raw.trim()
            if (s.isEmpty()) return null
            val isHttps = s.startsWith("https://", ignoreCase = true)
            s = s.removePrefix("http://").removePrefix("https://")
            s = s.substringBefore("/")        // 去掉路径
            if (s.isEmpty()) return null
            // 不再默认添加端口，保持用户输入的原样
            return if (isHttps) "https://$s" else s
        }

        /** 检测输入是否使用 HTTPS。 */
        fun isHttps(raw: String): Boolean =
            raw.trim().startsWith("https://", ignoreCase = true)
    }
}
