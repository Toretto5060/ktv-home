-- V19__strip_tv_prefix_from_device_id.sql
-- 清除 rooms.device_id 和 room_applications.device_id 中的 "tv-" 前缀。
-- 新版 APK 已不再生成带 "tv-" 前缀的 deviceId，
-- 老记录迁移后 TV 可正常重新申请。

UPDATE rooms SET device_id = SUBSTR(device_id, 4) WHERE device_id LIKE 'tv-%';
UPDATE room_applications SET device_id = SUBSTR(device_id, 4) WHERE device_id LIKE 'tv-%';
UPDATE blacklist SET device_id = SUBSTR(device_id, 4) WHERE device_id LIKE 'tv-%';
