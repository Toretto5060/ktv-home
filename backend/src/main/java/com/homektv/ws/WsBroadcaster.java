package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 会话注册与事件广播（P1.14，详设§4.1/§4.2）。
 * 使用 client_token 作为唯一标识，支持 30 分钟优雅离场宽限期。
 *
 * WebSocket session registration and event broadcasting (P1.14, detailed design §4.1/§4.2).
 * Uses client_token as unique identifier, supports 30-minute graceful disconnect grace period.
 */
@Component
public class WsBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(WsBroadcaster.class);

    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /** client_token -> SessionInfo */
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    /** 30 分钟宽限期（毫秒） */
    private static final long GRACE_PERIOD_MS = 30 * 60 * 1000L;

    public WsBroadcaster(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 会话信息，包含 WebSocket 会话和最后消息时间。
     */
    private static class SessionInfo {
        final String sessionId;
        final WebSocketSession session;
        final String roomId;
        volatile long lastMessageTime;
        volatile boolean pendingRemoval = false;

        SessionInfo(String sessionId, WebSocketSession session) {
            this.sessionId = sessionId;
            this.session = session;
            Object room = session.getAttributes().get("room_id");
            this.roomId = room == null ? null : room.toString();
            this.lastMessageTime = System.currentTimeMillis();
        }
    }

    /**
     * 注册 WebSocket 会话。
     * 如果该 client_token 已有活跃会话，先移除旧会话。
     *
     * Registers a WebSocket session.
     * If the client_token already has an active session, removes the old one first.
     *
     * @param session WebSocket 会话
     */
    public void register(WebSocketSession session) {
        String clientToken = getClientToken(session);
        String newSessionId = session.getId();

        // 取消该 client_token 的任何待移除定时器
        cancelPendingRemoval(clientToken);

        // 如果该 client_token 已有会话，先移除（避免重复计数）
        SessionInfo existing = sessions.get(clientToken);
        if (existing != null && !existing.sessionId.equals(newSessionId)) {
            log.debug("client_token {} 已有会话 {}，替换为新会话 {}", clientToken, existing.sessionId, newSessionId);
            try {
                existing.session.close(CloseStatus.NORMAL);
            } catch (IOException e) {
                log.debug("关闭旧会话失败: {}", e.getMessage());
            }
            sessions.remove(clientToken);
        }

        sessions.put(clientToken, new SessionInfo(newSessionId, session));
        log.debug("WS 会话注册: client_token={}, session={}", clientToken, newSessionId);
    }

    /**
     * 注销会话。
     * 启动 30 分钟宽限期，期间若重新连接则取消移除。
     *
     * Unregisters a session and starts a 30-minute grace period.
     *
     * @param session WebSocket 会话
     * @return client_type（tv/h5，可能为 null）
     */
    public String unregister(WebSocketSession session) {
        String clientToken = getClientToken(session);
        SessionInfo info = sessions.remove(clientToken);
        if (info == null) return null;

        String clientType = getClientType(session);
        log.debug("WS 会话断开，进入 {} 分钟宽限期: client_token={}, session={}",
                GRACE_PERIOD_MS / 60000, clientToken, session.getId());

        // 启动 30 分钟宽限期定时器
        scheduleGracePeriod(clientToken, clientType);

        return clientType;
    }

    public int sessionCount() {
        return sessions.size();
    }

    /**
     * 查找指定 deviceId 对应的会话并关闭。
     * 用于拒绝/拉黑设备时主动断开其 WS 连接。
     *
     * @param deviceId 要断开的设备 ID
     */
    public void disconnectDeviceById(String deviceId) {
        for (SessionInfo info : sessions.values()) {
            Object did = info.session.getAttributes().get("device_id");
            if (deviceId.equals(did)) {
                log.info("主动断开设备 {} 的 WS 会话 {}", deviceId, info.sessionId);
                try {
                    info.session.close(CloseStatus.NORMAL);
                } catch (IOException e) {
                    log.debug("关闭会话失败: {}", e.getMessage());
                }
                return;
            }
        }
    }

    /**
     * TV 是否在线。
     */
    public boolean isTvOnline() {
        return isTvOnline(null);
    }

    public boolean isTvOnline(String roomId) {
        return sessions.values().stream().anyMatch(info -> {
            Object type = info.session.getAttributes().get("client_type");
            return "tv".equals(type == null ? null : type.toString())
                    && (roomId == null || roomId.equals(info.roomId));
        });
    }

    /**
     * 已连接的 H5 手机数（去重，同一 client_token 只算一次）。
     */
    public long h5Count() {
        return h5Count(null);
    }

    /**
     * 统计指定房间的 H5 连接数；同一 client_token 已在 register 中去重。
     */
    public long h5Count(String roomId) {
        return sessions.values().stream().filter(info -> {
            Object type = info.session.getAttributes().get("client_type");
            return "h5".equals(type == null ? null : type.toString())
                    && (roomId == null || roomId.equals(info.roomId));
        }).count();
    }

    /**
     * 更新会话的最后消息时间。
     */
    public void touchSession(String sessionId) {
        for (SessionInfo info : sessions.values()) {
            if (info.sessionId.equals(sessionId)) {
                info.lastMessageTime = System.currentTimeMillis();
                break;
            }
        }
    }

    /**
     * 启动 30 分钟宽限期定时器。
     */
    private void scheduleGracePeriod(String clientToken, String clientType) {
        scheduler.schedule(() -> {
            // 宽限期结束后，检查该 client_token 是否已重新连接
            SessionInfo current = sessions.get(clientToken);
            if (current == null) {
                // 30 分钟内没有重新连接，真正移除
                log.debug("client_token {} 宽限期结束，已移除", clientToken);
            }
            // 如果已重新连接，sessions.get() 会返回非 null，之前的待移除状态被取消
        }, GRACE_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 取消待移除状态（重新连接时调用）。
     */
    private void cancelPendingRemoval(String clientToken) {
        SessionInfo info = sessions.get(clientToken);
        if (info != null) {
            info.pendingRemoval = false;
        }
    }

    /**
     * 向指定房间 ID 的 TV 会话发送事件（用于审批/拒绝等主动通知）。
     * 使用 room_id 而非 device_id 查找会话（因为 TV 在 PENDING 状态下 room_id 已正确设置）。
     *
     * @param roomId 目标房间 ID
     * @param event 要发送的事件
     */
    public void broadcastToRoom(String roomId, WsEvent event) {
        String json = serialize(event);
        log.info("broadcastToRoom: 查找房间 {}，当前会话数 {}", roomId, sessions.size());
        for (SessionInfo info : sessions.values()) {
            Object rid = info.session.getAttributes().get("room_id");
            log.info("  会话 {} 的 room_id: {}", info.sessionId, rid);
            if (rid != null && roomId.equals(rid.toString())) {
                Object clientType = info.session.getAttributes().get("client_type");
                if (!"tv".equals(clientType == null ? null : clientType.toString())) continue;
                log.info("  找到目标 TV 会话 {}，发送事件 {}", info.sessionId, event.type());
                send(info.session, json);
                return;
            }
        }
        log.warn("未找到房间 {} 的 TV 会话", roomId);
    }

    /**
     * 向单个会话发送事件。
     */
    public void sendTo(WebSocketSession session, WsEvent event) {
        send(session, serialize(event));
    }

    /**
     * 向所有在线会话广播事件。
     */
    public void broadcast(WsEvent event) {
        broadcast(null, event);
    }

    /** 向指定房间广播事件；roomId 为空时保留全局广播兼容行为。 */
    public void broadcast(String roomId, WsEvent event) {
        String json = serialize(event);
        log.info("广播事件 {}，房间 {}，当前在线会话数: {}", event.type(), roomId, sessions.size());
        for (SessionInfo info : sessions.values()) {
            if (roomId == null || roomId.equals(info.roomId)) {
                send(info.session, json);
            }
        }
    }

    private void send(WebSocketSession session, String json) {
        if (json == null || !session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.debug("发送失败: {}", session.getId());
        }
    }

    private String serialize(WsEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("事件序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private String getClientToken(WebSocketSession session) {
        Object token = session.getAttributes().get("client_token");
        return token != null ? token.toString() : session.getId();
    }

    private String getClientType(WebSocketSession session) {
        Object type = session.getAttributes().get("client_type");
        return type != null ? type.toString() : null;
    }
}
