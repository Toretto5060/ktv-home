package com.homektv.ws;

/**
 * WebSocket 广播事件（详设§4.2）。type + payload 的 JSON 消息。
 *
 * WebSocket broadcast event (Detailed Design §4.2).
 * A JSON message consisting of type + payload.
 */
public record WsEvent(String type, Object payload) {

    // 事件类型常量（详设§4.2）
    // Event type constants (Detailed Design §4.2)
    public static final String SYNC_FULL = "sync_full";
    public static final String QUEUE_UPDATED = "queue_updated";
    public static final String NOW_PLAYING = "now_playing";
    public static final String PLAYER_STATE = "player_state";
    public static final String PLAYBACK_RESTARTED = "playback_restarted";
    public static final String PROGRESS = "progress";
    public static final String VOLUME_CHANGED = "volume_changed";
    public static final String VOCAL_CHANGED = "vocal_changed";
    public static final String EFFECT_PLAY = "effect_play";
    public static final String TOAST = "toast";

    // 管理后台进度事件
    public static final String SCAN_PROGRESS = "scan_progress";
    public static final String SCRAPE_PROGRESS = "scrape_progress";
    public static final String TRANSCODE_PROGRESS = "transcode_progress";
    public static final String AI_TASK_PROGRESS = "ai_task_progress";
    public static final String ARTIST_SCRAPE_PROGRESS = "artist_scrape_progress";

    // 管理员通知事件
    public static final String APPLICATION_APPROVED = "application_approved";
    public static final String ROOM_DISABLED = "room_disabled";
    public static final String ROOM_DISSOLVED = "room_dissolved";
    public static final String ROOM_EXPIRED = "room_expired";
    public static final String ROOM_PROMOTED = "room_promoted";
    public static final String ROOM_NAME_CHANGED = "room_name_changed";
    public static final String ROOM_TIME_CHANGED = "room_time_changed";
    public static final String QR_CODE_REFRESHED = "qr_code_refreshed";
    public static final String MEMBER_JOINED = "member_joined";
    public static final String APPLICATION_EXPIRED = "application_expired";
    public static final String DEVICE_BLACKLISTED = "device_blacklisted";
    public static final String NEW_ROOM_APPLICATION = "new_room_application";
    public static final String DEVICE_APPROVED = "device_approved";
    public static final String DEVICE_PENDING = "device_pending";
    public static final String DEVICE_ROOM_NOT_OPEN = "device_room_not_open";
    public static final String DEVICE_IDLE = "device_idle";
    public static final String ROOM_LIST_UPDATED = "room_list_updated";

    /**
     * 创建 WsEvent 实例的静态工厂方法。
     *
     * Static factory method to create a WsEvent instance.
     *
     * @param type    事件类型 / event type
     * @param payload 事件负载 / event payload
     * @return 新的 WsEvent 实例 / a new WsEvent instance
     */
    public static WsEvent of(String type, Object payload) {
        return new WsEvent(type, payload);
    }
}
