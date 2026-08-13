package com.homektv.tv.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 局域网服务端自动扫描（替代手输 IP）。
 *
 * 详设§12.1 原本约定「不做组播自动发现，避免路由器组播策略坑」。
 * 这里按用户要求改为**主动 HTTP 子网探测**：不依赖组播/mDNS，
 * 对本机所在 /24 子网的常用部署端口逐个 GET /api/health，命中 service=home-ktv 即认定为服务端。
 * 优点：不受路由器组播/AP 隔离策略影响；缺点：仅覆盖 /24（家用够用）。
 *
 * Advantage: independent of multicast and AP-isolation policies. Limitation:
 * it only covers a /24 subnet, which is sufficient for typical home networks.
 */
class LanScanner {

    private val client = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_TIMEOUT_MS + 200, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** 扫描本机所在 /24 网段的所有候选端口，并逐台报告命中。 */
    suspend fun scanAll(
        onProgress: ((scanned: Int, total: Int) -> Unit)? = null,
        onFound: ((hostPort: String) -> Unit)? = null,
    ): List<String> =
        coroutineScope {
            val prefix = localSubnetPrefix() ?: return@coroutineScope emptyList()
            val targets = scanTargets(prefix)
            val total = targets.size
            val counter = java.util.concurrent.atomic.AtomicInteger(0)
            val found = ConcurrentHashMap.newKeySet<String>()
            for (batch in targets.chunked(MAX_CONCURRENT_PROBES)) {
                batch.map { hostPort ->
                    async(Dispatchers.IO) {
                        if (validate(hostPort) && found.add(hostPort)) {
                            onFound?.invoke(hostPort)
                        }
                        onProgress?.invoke(counter.incrementAndGet(), total)
                    }
                }.awaitAll()
            }
            targets.filter(found::contains)
        }

    internal fun scanTargets(prefix: String): List<String> = CANDIDATE_PORTS.flatMap { port ->
        (1..254).map { last -> "$prefix$last:$port" }
    }

    /** 单地址探测：GET http(s)://host:port/api/health，body 含 "home-ktv" 即命中。 */
    suspend fun validate(hostPort: String): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(PROBE_TIMEOUT_MS + 300) {
            try {
                val isHttps = hostPort.startsWith("https://", ignoreCase = true)
                val url = if (isHttps) hostPort else "http://$hostPort"
                val req = Request.Builder()
                    .url("$url/api/health")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    resp.isSuccessful && (resp.body?.string()?.contains("home-ktv") == true)
                }
            } catch (_: Exception) {
                false
            }
        } ?: false
    }

    /**
     * 取本机 IPv4 的 /24 前缀（如 192.168.1.）。
     * 优先非回环、非虚拟的 site-local 地址（192.168/10/172.16-31）。
     */
    private fun localSubnetPrefix(): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        val dot = ip.lastIndexOf('.')
                        if (dot > 0) return ip.substring(0, dot + 1)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 1500L
        private const val MAX_CONCURRENT_PROBES = 64
        internal val CANDIDATE_PORTS = listOf(8080, 80, 8000, 8081, 8090, 8888, 9000, 9090)
    }
}
