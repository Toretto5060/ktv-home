package com.homektv.web;

import com.homektv.library.SettingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * 服务端展示地址探测（详设§8 ADM-03「展示地址：自动探测+可手填」）。
 * <p>
 * Server display address detection (Detail Design §8 ADM-03: auto-detect + manual override).
 *
 * 优先级：
 *   1. settings 里手填的 display_address（管理员在后台设置）
 *   2. 自动探测本机局域网 IPv4（site-local，非回环/虚拟）
 *   3. 兜底 localhost
 * 端口读取 server.port，支持非默认部署端口。
 * <p>
 * Priority:
 *   1. Manually configured display_address in settings (set by admin)
 *   2. Auto-detect the machine's LAN IPv4 (site-local, non-loopback, non-virtual)
 *   3. Fallback to localhost
 * Port is read from server.port, supporting non-default deployment ports.
 */
@Service
public class ServerAddressService {

    private final SettingService settingService;
    private final int serverPort;

    public ServerAddressService(SettingService settingService, @Value("${server.port:8080}") int serverPort) {
        this.settingService = settingService;
        this.serverPort = serverPort;
    }

    /**
     * 返回 host[:port]（不含协议），如 192.168.1.10:8080。
     * <p>
     * Returns host[:port] (without protocol), e.g. 192.168.1.10:8080.
     * @return the host and port string
     */
    public String hostPort() {
        // 1. 后台手填优先
        String manual = manualAddress();
        if (manual != null) return manual;
        // 2. 自动探测局域网地址
        String lan = detectLanIp();
        String host = lan != null ? lan : "localhost";
        return host + ":" + serverPort;
    }

    /**
     * 请求来自 TV 时优先使用请求实际访问的 host。容器端看到的网卡地址通常是
     * 172.x Docker 网段，而 Host 头保留了 TV 可达的宿主机地址。
     * <p>
     * When the request comes from a TV, prefer the actual host from the request.
     * The container's network interfaces usually show 172.x Docker subnet addresses,
     * while the Host header retains the host address reachable by the TV.
     * @param requestHost  the host from the request
     * @param requestPort  the port from the request
     * @return host[:port] string
     */
    public String hostPort(String requestHost, int requestPort) {
        String manual = manualAddress();
        if (manual != null) return manual;
        if (requestHost != null && !requestHost.isBlank()
                && !"localhost".equalsIgnoreCase(requestHost)
                && !requestHost.startsWith("127.")) {
            int port = requestPort > 0 ? requestPort : serverPort;
            return requestHost + ":" + port;
        }
        return hostPort();
    }

    /**
     * H5 点歌地址：http://host:port/m?room=default
     * <p>
     * H5 song-request URL: http://host:port/m?room=default
     * @param room  the room identifier
     * @return the full H5 URL
     */
    public String h5Url(String room) {
        return "http://" + hostPort() + "/m?room=" + room;
    }

    /**
     * H5 点歌地址，使用请求中的 host 和 port。
     * <p>
     * H5 song-request URL using the host and port from the request.
     * @param room         the room identifier
     * @param requestHost  the host from the request
     * @param requestPort  the port from the request
     * @return the full H5 URL
     */
    public String h5Url(String room, String requestHost, int requestPort) {
        return "http://" + hostPort(requestHost, requestPort) + "/m?room=" + room;
    }

    /**
     * H5 点歌地址：从当前 HTTP 请求的转发头或 Host 头解析外部访问地址。
     * 用于 QR 接口在反向代理/容器化部署下，默认值不能依赖 request.getServerName()（容器内网地址）。
     * <p>
     * H5 song-request URL resolved from forwarded headers / Host header.
     * Used by the QR endpoint when no explicit base_url is supplied — under
     * reverse proxies / containers request.getServerName() returns the upstream
     * LAN address, which would produce a wrong QR.
     * @param room    the room identifier
     * @param request the HTTP request
     * @return the full H5 URL
     */
    public String h5Url(String room, HttpServletRequest request) {
        String manual = manualAddress();
        if (manual != null) return "http://" + manual + "/m?room=" + room;

        // 1) X-Forwarded-Host（多代理时取第一个）+ X-Forwarded-Port + X-Forwarded-Proto
        String fwdHost = firstHeader(request, "X-Forwarded-Host");
        String fwdProto = firstHeader(request, "X-Forwarded-Proto");
        int port = parsePort(firstHeader(request, "X-Forwarded-Port"), serverPort);
        // 2) Host 头（带或不带端口）
        String hostHeader = firstHeader(request, "Host");

        String host = fwdHost != null ? fwdHost : hostHeader;
        if (host != null && !host.isBlank()
                && !"localhost".equalsIgnoreCase(host)
                && !host.startsWith("127.")) {
            String hostOnly = host;
            int hostPort = port;
            // 解析 host:port（IPv4 / DNS），不做 IPv6 复杂解析
            int colon = hostOnly.lastIndexOf(':');
            if (colon > 0 && hostOnly.indexOf(':') == hostOnly.lastIndexOf(':') && hostOnly.charAt(0) != '[') {
                try {
                    hostPort = Integer.parseInt(hostOnly.substring(colon + 1));
                    hostOnly = hostOnly.substring(0, colon);
                } catch (NumberFormatException ignored) {
                }
            } else {
                hostPort = hostPort > 0 ? hostPort : request.getServerPort();
            }
            String scheme = (fwdProto != null && !fwdProto.isBlank()) ? fwdProto : request.getScheme();
            return scheme + "://" + hostOnly + ":" + hostPort + "/m?room=" + room;
        }

        // 3) 用 serverPort / serverName 兜底
        return "http://" + hostPort() + "/m?room=" + room;
    }

    private static String firstHeader(HttpServletRequest req, String name) {
        String v = req.getHeader(name);
        if (v == null || v.isBlank()) return null;
        // X-Forwarded-Host: h1, h2  — 取第一个
        int comma = v.indexOf(',');
        return (comma >= 0 ? v.substring(0, comma) : v).trim();
    }

    private static int parsePort(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    /**
     * H5 点歌地址，由 TV 端传入当前连接的 base（H5 入口 URL）。扫码地址与 TV
     * 当前连接保持一致——TV 配内网地址就扫内网，配公网地址就扫公网，无需后端感知。
     * <p>
     * H5 song-request URL built from a TV-supplied base. Keeps the scanned address
     * in sync with whatever the TV is currently connected to (LAN or public).
     * @param room    the room identifier
     * @param baseUrl the H5 entry URL (protocol + host + port + /m)
     * @return the full H5 URL with room query
     */
    public String h5Url(String room, String baseUrl) {
        String base = baseUrl.trim();
        int q = base.indexOf('?');
        if (q >= 0) base = base.substring(0, q);
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.endsWith("/m")) base = base + "/m";
        return base + "?room=" + room;
    }

    private String manualAddress() {
        Object manual = settingService.getAll().get("display_address");
        if (manual instanceof String s && !s.isBlank()) return normalize(s.trim());
        return null;
    }

    /** 补全端口、剥离协议/路径。 */
    private String normalize(String raw) {
        String s = raw;
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (!s.contains(":")) s = s + ":" + serverPort;
        return s;
    }

    /** 探测本机首个站点本地 IPv4（192.168/10/172.16-31）。 */
    private String detectLanIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
