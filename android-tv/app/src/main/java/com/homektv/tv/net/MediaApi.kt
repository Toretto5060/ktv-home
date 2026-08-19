package com.homektv.tv.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * TV 端 REST 客户端（P1.28）。
 *
 * now_playing 快照只携带 song.id，而拉流接口 GET /api/stream/{file_id} 需要
 * file_id（= song_files.id）。播放前先拉 GET /api/songs/{id} 详情，从 files
 * 里取 priority 最高的文件源，得到 fileId（顺带拿 vocalTrackIndex 供 P1.29 切轨）。
 *
 * The TV REST client resolves the highest-priority file source before playback
 * and also provides the vocal track index used by P1.29 track switching.
 */
class MediaApi(private val config: AppConfig) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * 拉歌曲详情，返回 priority 最高的文件源；失败或无文件返回 null。
     *
     * Fetches song details and returns the highest-priority file source, or null on failure.
     */
    suspend fun bestFileSource(songId: Long): FileSource? = withContext(Dispatchers.IO) {
        val url = "${config.apiBase()}/songs/$songId"
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "detail $songId http ${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                val detail = json.decodeFromString(SongDetail.serializer(), body)
                detail.files.maxByOrNull { it.priority }
            }
        } catch (e: Exception) {
            Log.w(TAG, "detail $songId failed: ${e.message}")
            null
        }
    }

    suspend fun fetchLyric(songId: Long): String? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("${config.apiBase()}/lyric/$songId").build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) { null }
    }

    suspend fun fetchCover(songId: Long): ByteArray? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("${config.apiBase()}/cover/$songId").build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (_: Exception) { null }
    }

    suspend fun control(action: String, params: String = "{}"): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = "{\"action\":\"$action\",\"params\":$params}".toRequestBody("application/json".toMediaType())
            http.newCall(Request.Builder().url("${config.apiBase()}/control").post(body).build()).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    suspend fun fetchRecommendations(): List<SongDto> = withContext(Dispatchers.IO) {
        fun fetch(path: String): List<SongDto> {
            return try {
                http.newCall(Request.Builder().url("${config.apiBase()}$path").build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "recommendations $path http ${resp.code}")
                        return emptyList()
                    }
                    json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(SongDto.serializer()),
                        resp.body?.string().orEmpty(),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "recommendations $path failed: ${e.message}")
                emptyList()
            }
        }

        val ranked = fetch("/ranking?days=3650")
        val newest = fetch("/songs/new")
        (ranked + newest).distinctBy { it.id }
    }

    suspend fun fetchLibraryCount(): Long? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("${config.apiBase()}/admin/status").build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "library status http ${resp.code}")
                    return@withContext null
                }
                json.decodeFromString(LibraryStatus.serializer(), resp.body?.string().orEmpty()).totalSongs
            }
        } catch (e: Exception) {
            Log.w(TAG, "library status failed: ${e.message}")
            null
        }
    }

    suspend fun fetchStandbyContent(): StandbyContent = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("${config.apiBase()}/standby/content").build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext StandbyContent()
                json.decodeFromString(StandbyContent.serializer(), resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            Log.w(TAG, "standby content failed: ${e.message}")
            StandbyContent()
        }
    }

    suspend fun fetchReleaseInfo(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("${config.apiBase()}/release").build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "release info http ${resp.code}")
                    return@withContext null
                }
                json.decodeFromString(ReleaseInfo.serializer(), resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            Log.w(TAG, "release info failed: ${e.message}")
            null
        }
    }

    /** Streams an APK into a temporary file and only exposes it after a complete response. */
    suspend fun downloadApk(path: String, destination: File, expectedSize: Long = 0): Boolean = withContext(Dispatchers.IO) {
        val temporary = File(destination.parentFile, "${destination.name}.part")
        try {
            destination.parentFile?.mkdirs()
            temporary.delete()
            val url = if (path.startsWith("http://") || path.startsWith("https://")) {
                path
            } else {
                "${config.apiBase().removeSuffix("/api")}${if (path.startsWith('/')) path else "/$path"}"
            }
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "APK download http ${response.code}")
                    return@withContext false
                }
                val body = response.body ?: return@withContext false
                temporary.outputStream().buffered().use { output -> body.byteStream().use { it.copyTo(output) } }
            }
            if (temporary.length() <= 0 || (expectedSize > 0 && temporary.length() != expectedSize)) {
                Log.w(TAG, "APK size mismatch: expected=$expectedSize actual=${temporary.length()}")
                return@withContext false
            }
            if (destination.exists() && !destination.delete()) return@withContext false
            temporary.renameTo(destination)
        } catch (e: Exception) {
            Log.w(TAG, "APK download failed: ${e.message}")
            false
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    suspend fun fetchUrl(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val url = if (path.startsWith("http")) path else "${config.apiBase().removeSuffix("/api")}$path"
            http.newCall(Request.Builder().url(url).build()).execute().use { response -> if (response.isSuccessful) response.body?.bytes() else null }
        } catch (_: Exception) { null }
    }

    /** 拉流地址：http://host/api/stream/{fileId} */
    fun streamUrl(fileId: Long): String = "${config.apiBase()}/stream/$fileId"

    /**
     * 二维码地址：http://host/api/qr?size=xxx&content=xxx（P1.30 待机页扫码引导）。
     * TV 端直接拼好扫码内容（h5Url + "?room=default"），后端只负责 ZXing 编码，不做任何覆盖。
     * 这样 TV 端用自己"已知对外可达"的 URL 拼内容，扫码结果严格跟 TV 当前连接一致 —— 不依赖任何反代/Host 头兜底。
     * <p>
     * QR endpoint. The TV composes the full scanned string (h5Url + "?room=default")
     * and the backend only ZXing-encodes it without altering the value. The TV uses
     * whatever URL it already knows is reachable for the phone, independent of any
     * reverse proxy / Host header.
     */
    fun qrUrl(size: Int): String {
        val h5 = config.h5Url()
        // 没配 serverHost 时，h5 形如 "http:///m"。传给后端意义不大，跳过 content，
        // 让后端走 base_url fallback（旧链路）或干脆 400 而不是吐出坏码。
        // <p>When serverHost is unconfigured, h5 looks like "http:///m" — sending it
        // would encode a broken URL. Skip content so the backend falls back to its
        // legacy base_url/host path (or 400s) rather than producing a bad QR.
        if (config.serverHost.isNullOrBlank() || h5.contains(":///")) {
            return "${config.apiBase()}/qr?size=$size"
        }
        // content 走 base_url 同一套 room 逻辑；这里直接拼完整字符串更稳：
        // 后端不再"在末尾 +?room"，不会再出错。
        // <p>Compose the full string here so the backend can't accidentally append
        // ?room twice or rewrite the URL.
        val content = "$h5?room=default"
        val encoded = java.net.URLEncoder.encode(content, "UTF-8")
        return "${config.apiBase()}/qr?size=$size&content=$encoded"
    }

    /** 拉取二维码 PNG 字节；失败返回 null。 */
    suspend fun fetchQr(size: Int): ByteArray? = withContext(Dispatchers.IO) {
        // 加时间戳参数绕过 OkHttp 缓存/CDN 缓存，确保刷新时拿到最新图片
        val url = "${qrUrl(size)}&_t=${System.currentTimeMillis()}"
        try {
            http.newCall(Request.Builder().url(url)
                .header("Cache-Control", "no-cache")
                .build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "qr http ${resp.code} url=$url")
                    return@withContext null
                }
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "qr fetch failed: ${e.message} url=$url")
            null
        }
    }

    /**
     * 查询 TV 设备授权状态（P1.16 TV 侧进入待机页后的校验）。
     *
     * <p>返回结构：
     * <ul>
     *   <li>status = "approved" → roomId/roomName 不为 null</li>
     *   <li>status = "pending" → applicationId/expiredAt 不为 null</li>
     *   <li>status = "blacklisted" / "room_not_open" → 其余字段为 null</li>
     * </ul>
     *
     * <p>网络异常 / 接口返回非 2xx 时返回 {@link TvAuthorizeResult#networkError}（status="network_error"），
     * 由调用方决定是否 30s 后重试。
     */
    suspend fun authorize(deviceId: String): TvAuthorizeResult = withContext(Dispatchers.IO) {
        val url = "${config.apiBase()}/tv/authorize?device_id=${java.net.URLEncoder.encode(deviceId, "UTF-8")}"
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "authorize http ${resp.code}")
                    return@withContext when (resp.code) {
                        403 -> TvAuthorizeResult(status = "blacklisted")
                        404 -> TvAuthorizeResult(status = "network_error")
                        else -> TvAuthorizeResult.networkError()
                    }
                }
                val body = resp.body?.string() ?: return@withContext TvAuthorizeResult.networkError()
                val obj = json.parseToJsonElement(body).let { it as? kotlinx.serialization.json.JsonObject }
                    ?: return@withContext TvAuthorizeResult.networkError()
                val status = obj["status"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: return@withContext TvAuthorizeResult.networkError()
                when (status) {
                    "approved" -> TvAuthorizeResult(
                        status = "approved",
                        roomId = (obj["room_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                        roomName = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                        applicationId = null,
                        expiredAt = null,
                    )
                    "pending" -> TvAuthorizeResult(
                        status = "pending",
                        roomId = null,
                        roomName = null,
                        applicationId = (obj["application_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                        expiredAt = (obj["expired_at"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                    )
                    "blacklisted" -> TvAuthorizeResult(status = "blacklisted", roomId = null, roomName = null, applicationId = null, expiredAt = null)
                    "room_not_open" -> TvAuthorizeResult(status = "room_not_open", roomId = null, roomName = null, applicationId = null, expiredAt = null)
                    else -> TvAuthorizeResult.networkError()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "authorize failed: ${e.message}")
            TvAuthorizeResult.networkError()
        }
    }

    companion object {
        private const val TAG = "MediaApi"
    }
}
