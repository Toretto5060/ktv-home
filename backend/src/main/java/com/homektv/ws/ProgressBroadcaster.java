package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 管理后台进度事件广播器。
 * 封装 WsBroadcaster，对扫描/刮削/转码进度进行节流广播（每 N 次更新或状态切换时触发），
 * 避免 WebSocket 消息过于频繁。
 *
 * Admin progress event broadcaster.
 * Wraps WsBroadcaster with throttling (every N updates or on state change),
 * preventing WebSocket flooding.
 */
@Component
public class ProgressBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ProgressBroadcaster.class);

    private final WsBroadcaster broadcaster;
    private final ObjectMapper mapper;

    // 节流计数器（每类进度独立计数）
    private int scanCount = 0;
    private int transcodeCount = 0;
    private int scrapeCount = 0;
    private int aiCount = 0;

    // 上次广播时的 running 状态，用于检测状态切换
    private boolean lastScanRunning = false;
    private boolean lastTranscodeRunning = false;

    // 每隔 N 次更新才广播（0 表示每次都广播）
    private static final int SCAN_THROTTLE = 5;
    private static final int TRANSCODE_THROTTLE = 1;
    private static final int SCRAPE_THROTTLE = 0;  // scrape 是数据库操作，不频繁
    private static final int AI_THROTTLE = 0;

    public ProgressBroadcaster(WsBroadcaster broadcaster, ObjectMapper mapper) {
        this.broadcaster = broadcaster;
        this.mapper = mapper;
    }

    private boolean shouldBroadcast(int count, int throttle) {
        return throttle == 0 || count == 0 || count % throttle == 0;
    }

    /**
     * 广播扫描进度。状态从停止变为运行时重置计数器并立即广播。
     */
    public void broadcastScanProgress(String eventType, Object payload, boolean running) {
        if (running != lastScanRunning) {
            scanCount = 0;
            lastScanRunning = running;
        }
        if (shouldBroadcast(++scanCount, SCAN_THROTTLE)) {
            broadcaster.broadcast(WsEvent.of(eventType, payload));
        }
    }

    /**
     * 广播转码进度。状态从停止变为运行时重置计数器并立即广播。
     */
    public void broadcastTranscodeProgress(Object payload, boolean running) {
        if (running != lastTranscodeRunning) {
            transcodeCount = 0;
            lastTranscodeRunning = running;
        }
        if (shouldBroadcast(++transcodeCount, TRANSCODE_THROTTLE)) {
            broadcaster.broadcast(WsEvent.of(WsEvent.TRANSCODE_PROGRESS, payload));
        }
    }

    /**
     * 广播元数据刮削进度（每批次结束时广播，或每次 item 状态变化时）。
     */
    public void broadcastScrapeProgress(Object payload) {
        if (shouldBroadcast(++scrapeCount, SCRAPE_THROTTLE)) {
            broadcaster.broadcast(WsEvent.of(WsEvent.SCRAPE_PROGRESS, payload));
        }
    }

    /**
     * 广播 AI 任务进度（任务状态变化时广播）。
     */
    public void broadcastAiProgress(Object payload) {
        if (shouldBroadcast(++aiCount, AI_THROTTLE)) {
            broadcaster.broadcast(WsEvent.of(WsEvent.AI_TASK_PROGRESS, payload));
        }
    }

    /**
     * 向所有 H5 客户端广播事件（不经过节流，直接发送）。
     */
    public void broadcastToH5(String eventType, Object payload) {
        broadcaster.broadcast(WsEvent.of(eventType, payload));
    }
}
