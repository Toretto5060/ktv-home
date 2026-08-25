package com.homektv.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.library.RoomService;
import com.homektv.queue.PlaybackService;
import com.homektv.queue.SnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.UUID;

/**
 * KTV WebSocket 处理器（P1.13/P1.15/P1.16，详设§4.1/§4.2）。
 * - 连接建立即推送 sync_full 全量快照
 * - 接收 TV 上行 progress → 转发广播给 H5（歌词/进度同步）
 * - 接收 ping → 回 pong（心跳）
 * - TV 设备注册 → 验证设备状态（黑名单/审批）
 *
 * KTV WebSocket handler (P1.13/P1.15/P1.16, detailed design §4.1/§4.2).
 * - Pushes a sync_full full snapshot upon connection establishment.
 * - Receives progress messages from TV → broadcasts to H5 clients (lyrics/progress sync).
 * - Receives ping → replies with pong (heartbeat).
 * - TV device registration → validates device status (blacklist/approval).
 */
@Component
public class KtvWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(KtvWebSocketHandler.class);

    private final WsBroadcaster broadcaster;
    private final SnapshotService snapshotService;
    private final PlaybackService playbackService;
    private final TvOfflineWatcher tvOfflineWatcher;
    private final RoomService roomService;
    private final ObjectMapper mapper;

    public KtvWebSocketHandler(WsBroadcaster broadcaster, SnapshotService snapshotService,
                               PlaybackService playbackService, TvOfflineWatcher tvOfflineWatcher,
                               RoomService roomService, ObjectMapper mapper) {
        this.broadcaster = broadcaster;
        this.snapshotService = snapshotService;
        this.playbackService = playbackService;
        this.tvOfflineWatcher = tvOfflineWatcher;
        this.roomService = roomService;
        this.mapper = mapper;
    }

    /**
     * 连接建立后注册会话并推送全量快照；若为 TV 端则触发上线监听。
     * TV设备需要注册验证（黑名单/审批状态）。
     *
     * Registers the session and pushes a full snapshot upon connection
     * establishment; triggers online watcher if the client is a TV.
     *
     * @param session WebSocket 会话 / WebSocket session
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        RoomService.ConnectResult tvProbe = null;

        // TV 端的房间由 device_id 在服务端确定，不能依赖客户端猜测 room_id。
        if (isTv(session)) {
            Object deviceIdObj = session.getAttributes().get("device_id");
            String deviceId = deviceIdObj != null ? deviceIdObj.toString() : null;
            if (deviceId != null) {
                tvProbe = roomService.connect(deviceId);
                if ("blacklisted".equals(tvProbe.status)) {
                    session.close(CloseStatus.POLICY_VIOLATION);
                    log.info("TV设备 {} 在黑名单中，拒绝连接", deviceId);
                    return;
                }
                // 必须在 register() 之前设置 room_id，否则 SessionInfo 快照为 null
                if (tvProbe.room != null) {
                    session.getAttributes().put("room_id", tvProbe.room.getId().toString());
                }
            }
        }

        // register() 会创建 SessionInfo 并快照 room_id，必须在 room_id 设置之后调用
        broadcaster.register(session);

        if (tvProbe != null) {
            broadcaster.sendTo(session, tvProbe.toWsEvent());
            tvOfflineWatcher.onTvConnected();
        }

        // 连接/重连只发送当前房间的快照。
        String roomId = roomId(session);
        broadcaster.sendTo(session, WsEvent.of(WsEvent.SYNC_FULL, snapshotService.snapshot(roomId)));
        log.debug("WS 连接建立: {}，房间 {}，当前在线 {}", session.getId(), roomId, broadcaster.sessionCount());
    }

    /**
     * 处理上行消息：ping/pong 心跳、TV 播放进度广播、曲目完成/播放错误切歌。
     *
     * Handles incoming messages: ping/pong heartbeat, TV playback progress
     * broadcast, track finished / play-error skip and broadcast.
     *
     * @param session WebSocket 会话 / WebSocket session
     * @param message 文本消息 / text message
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        broadcaster.touchSession(session.getId());
        JsonNode node = mapper.readTree(message.getPayload());
        String type = node.path("type").asText("");

        switch (type) {
            case "ping" -> broadcaster.sendTo(session, WsEvent.of("pong", null));
            case "progress" -> {
                // TV 上行播放进度 → 转发给所有端（详设§4.2 progress）
                long positionMs = node.path("payload").path("position_ms").asLong(0);
                broadcaster.broadcast(roomId(session), WsEvent.of(WsEvent.PROGRESS,
                        java.util.Map.of("position_ms", positionMs)));
            }
            case "finished" -> {
                // TV 上报当前曲目播放完成 → 推进队列并广播
                playbackService.onFinished();
                broadcaster.broadcast(roomId(session), WsEvent.of(WsEvent.NOW_PLAYING, snapshotService.snapshot(roomId(session))));
            }
            case "play_error" -> {
                // TV 无法读取当前媒体时按异常切歌，避免队列卡死；将原因同步给手机端。
                String reason = node.path("payload").path("message").asText("媒体读取失败");
                Long fileId = node.path("payload").path("file_id").isNumber()
                        ? node.path("payload").path("file_id").asLong() : null;
                playbackService.onPlayError(fileId);
                broadcaster.broadcast(roomId(session), WsEvent.of(WsEvent.TOAST,
                        java.util.Map.of("text", "当前歌曲播放失败，已自动切换下一首：" + reason)));
                broadcaster.broadcast(roomId(session), WsEvent.of(WsEvent.NOW_PLAYING, snapshotService.snapshot(roomId(session))));
            }
            default -> log.debug("未知 WS 消息类型: {}", type);
        }
    }

    /**
     * 连接关闭时注销会话；若为 TV 端则触发离线监听。
     *
     * Unregisters the session on close; triggers offline watcher if the
     * client was a TV.
     *
     * @param session WebSocket 会话 / WebSocket session
     * @param status  关闭状态 / close status
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        notifyOfflineIfTv(broadcaster.unregister(session));
        log.debug("WS 连接关闭: {}，剩余在线 {}", session.getId(), broadcaster.sessionCount());
    }

    /**
     * 传输异常时注销会话并记录日志；若为 TV 端则触发离线监听。
     *
     * Unregisters the session and logs the error on transport failure;
     * triggers offline watcher if the client was a TV.
     *
     * @param session   WebSocket 会话 / WebSocket session
     * @param exception 异常 / the exception
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WS 传输错误 {}: {}", session.getId(), exception.getMessage());
        notifyOfflineIfTv(broadcaster.unregister(session));
    }

    private boolean isTv(WebSocketSession session) {
        Object type = session.getAttributes().get("client_type");
        return "tv".equals(type == null ? null : type.toString());
    }

    private String roomId(WebSocketSession session) {
        Object room = session.getAttributes().get("room_id");
        return room == null ? null : room.toString();
    }

    private void notifyOfflineIfTv(String clientType) {
        if ("tv".equals(clientType)) {
            tvOfflineWatcher.onTvDisconnected();
        }
    }
}
