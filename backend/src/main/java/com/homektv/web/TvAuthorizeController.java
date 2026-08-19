package com.homektv.web;

import com.homektv.domain.Room;
import com.homektv.domain.RoomApplication;
import com.homektv.library.RoomService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * TV 设备授权查询接口（P1.16 TV 侧）。
 *
 * <p>TV 进入待机页面后调用本接口获取当前设备授权状态，避免在 WebSocket 握手阶段就做
 * 授权判断（保持「连接 → 待机 → 授权校验」的语义，无论 TV 是从 SetupActivity 进入还是
 * 从快速重连恢复 savedServers 进入，都走同一条路径）。
 *
 * <p>返回结构示例：
 * <pre>
 *   {"status":"approved"}                              // 已批准，可用
 *   {"status":"pending","application_id":"...","expired_at":"..."}  // 等待审批
 *   {"status":"room_not_open"}                          // 已批准但不在开放时段
 *   {"status":"blacklisted"}                            // 黑名单（应清掉 savedServers）
 * </pre>
 *
 * <p>QR 码及房间详情从 sync_full 快照里拿，本接口不重复返回。
 */
@RestController
@RequestMapping("/api/tv")
public class TvAuthorizeController {

    private final RoomService roomService;

    public TvAuthorizeController(RoomService roomService) {
        this.roomService = roomService;
    }

    /**
     * 查询 TV 设备的授权状态。
     *
     * @param deviceId TV 端 device_id（必填）
     * @return 授权结果 map
     */
    @GetMapping("/authorize")
    public Map<String, Object> authorize(@RequestParam("device_id") String deviceId) {
        RoomService.ConnectResult result = roomService.connect(deviceId);
        Map<String, Object> body = new HashMap<>();
        body.put("status", result.status);
        switch (result.status) {
            case "pending" -> {
                RoomApplication app = result.application;
                body.put("application_id", app.getId().toString());
                // ISO-8601 UTC 带 Z，TV 端 parseIsoToMs() 解析
                body.put("expired_at", app.getExpiredAt().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            }
            case "approved" -> {
                Room room = result.room;
                body.put("room_id", room.getId().toString());
                body.put("name", room.getName());
            }
            case "blacklisted", "room_not_open" -> {
                // 仅返回 status，调用方按需处理
            }
            default -> {
                // 未知状态，原样返回 status
            }
        }
        return body;
    }
}