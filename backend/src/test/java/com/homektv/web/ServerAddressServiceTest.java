package com.homektv.web;

import com.homektv.library.SettingService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServerAddressServiceTest {

    @Test
    void usesReachableRequestHostInsteadOfContainerInterface() {
        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of());
        String requestHost = "xxx.xxx.xxx";
        int requestPort = 8080;
        ServerAddressService service = new ServerAddressService(settings, requestPort);

        assertEquals("http://" + requestHost + ":" + requestPort + "/m?room=default",
                service.h5Url("default", requestHost, requestPort));
    }

    @Test
    void manualDisplayAddressStillHasHighestPriority() {
        SettingService settings = mock(SettingService.class);
        String manualHost = "xxx.xxx.xxx";
        int manualPort = 8080;
        String manual = "http://" + manualHost + ":" + manualPort + "/m";
        when(settings.getAll()).thenReturn(Map.of("display_address", manual));
        ServerAddressService service = new ServerAddressService(settings, 8080);

        assertEquals(manual + "?room=room-a",
                service.h5Url("room-a", "xxx.xxx.xxx", 8080));
    }

    /**
     * 验证 h5Url(room, baseUrl) 透传 TV 端传入的 base，不做任何转换。
     * 测试不硬编码任何具体 IP/域名——输入和期望都从变量构造。
     * <p>
     * Verifies h5Url(room, baseUrl) passes through whatever base the TV sends.
     * No hard-coded IPs/domains — inputs and expectations are built from variables.
     */
    @Test
    void h5UrlFromBaseUrlPassesThroughHttp() {
        String protocol = "http";
        String host = "xxx.xxx.xxx";
        int port = 8080;
        verifyPassThrough(protocol, host, port, "default");
    }

    @Test
    void h5UrlFromBaseUrlPassesThroughHttps() {
        String protocol = "https";
        String host = "xxx.xxx.xxx";
        int port = 8080;
        verifyPassThrough(protocol, host, port, "room-a");
    }

    private void verifyPassThrough(String protocol, String host, int port, String room) {
        String baseIn = protocol + "://" + host + ":" + port + "/m";
        String expected = baseIn + "?room=" + room;

        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of());
        ServerAddressService service = new ServerAddressService(settings, port);

        assertEquals(expected, service.h5Url(room, baseIn));
    }

    /**
     * 规范化输入：剥离尾斜杠和已有的 query 后再附加 ?room=。
     * 测试不硬编码具体 IP/域名——所有值从变量构造。
     * <p>
     * Normalizes trailing slash and existing query before appending ?room=.
     * No hard-coded IPs/domains — values are built from variables.
     */
    @Test
    void h5UrlFromBaseUrlNormalizesTrailingSlash() {
        String protocol = "http";
        String host = "xxx.xxx.xxx";
        int port = 8080;
        String baseIn = protocol + "://" + host + ":" + port + "/m/";
        String expected = protocol + "://" + host + ":" + port + "/m?room=default";

        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of());
        ServerAddressService service = new ServerAddressService(settings, port);

        assertEquals(expected, service.h5Url("default", baseIn));
    }

    @Test
    void h5UrlFromBaseUrlNormalizesExistingQuery() {
        String protocol = "https";
        String host = "xxx.xxx.xxx";
        int port = 8080;
        String baseIn = protocol + "://" + host + ":" + port + "/m?foo=bar";
        String expected = protocol + "://" + host + ":" + port + "/m?room=room-a";

        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of());
        ServerAddressService service = new ServerAddressService(settings, port);

        assertEquals(expected, service.h5Url("room-a", baseIn));
    }

    /**
     * 反向代理场景：request.getServerName() 拿到容器内网 IP，但 Host 头是公网域名。
     * 扫码必须返回公网域名的 H5 地址，不能是内网。
     * <p>Reverse proxy: request.getServerName() returns the container/LAN IP,
     * but the Host header carries the public domain. The QR must reflect the
     * public domain, not the upstream LAN IP.
     */
    @Test
    void h5UrlPrefersHostHeaderOverServerName() {
        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of());
        ServerAddressService service = new ServerAddressService(settings, 8080);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-Host")).thenReturn(null);
        when(req.getHeader("X-Forwarded-Proto")).thenReturn(null);
        when(req.getHeader("X-Forwarded-Port")).thenReturn(null);
        when(req.getHeader("Host")).thenReturn("example.com:1028");
        when(req.getServerName()).thenReturn("192.168.5.26");
        when(req.getServerPort()).thenReturn(8080);
        when(req.getScheme()).thenReturn("http");

        String url = service.h5Url("default", req);
        assertEquals("http://example.com:1028/m?room=default", url);
    }

    /**
     * Nginx/Caddy 等反代通常会把原始 Host/Proto/Port 透传到 X-Forwarded-* 头；
     * 后端必须用这些头还原外部可访问的 H5 地址。
     * <p>X-Forwarded-Host/Proto/Port should win when present, so the QR
     * resolves the externally reachable H5 URL, not the upstream.
     */
    @Test
    void h5UrlPrefersXForwardedHeaders() {
        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of());
        ServerAddressService service = new ServerAddressService(settings, 8080);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-Host")).thenReturn("example.com");
        when(req.getHeader("X-Forwarded-Proto")).thenReturn("https");
        when(req.getHeader("X-Forwarded-Port")).thenReturn("443");
        when(req.getHeader("Host")).thenReturn("localhost:8080");
        when(req.getServerName()).thenReturn("172.17.0.2");
        when(req.getServerPort()).thenReturn(8080);
        when(req.getScheme()).thenReturn("http");

        String url = service.h5Url("default", req);
        assertEquals("https://example.com:443/m?room=default", url);
    }

    /**
     * manualAddress 在 settings 里被设成内网 IP 时优先（管理员显式配置）。
     * <p>manualAddress still wins when configured — admin's explicit choice
     * takes priority over forwarded/host headers.
     */
    @Test
    void manualDisplayAddressOverridesForwardedHeaders() {
        SettingService settings = mock(SettingService.class);
        when(settings.getAll()).thenReturn(Map.of("display_address", "10.0.0.5:8080"));
        ServerAddressService service = new ServerAddressService(settings, 8080);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Host")).thenReturn("example.com:1028");
        when(req.getServerName()).thenReturn("192.168.5.26");
        when(req.getServerPort()).thenReturn(8080);
        when(req.getScheme()).thenReturn("http");

        assertEquals("http://10.0.0.5:8080/m?room=default", service.h5Url("default", req));
    }
}
