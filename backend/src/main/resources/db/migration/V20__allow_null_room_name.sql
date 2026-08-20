-- V20: 允许房间名称为 null，表示未重命名
-- APK 将显示默认标题 "HOME KTV" 而非 deviceId

ALTER TABLE rooms ALTER COLUMN name DROP NOT NULL;
