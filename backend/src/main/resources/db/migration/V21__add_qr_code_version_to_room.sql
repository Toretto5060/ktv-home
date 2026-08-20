-- V21: 房间表加 qr_code_version 字段，用于让旧 QR 失效
-- 每次 generateQrCode() 自增 1；token 内嵌 version，joinRoom 校验 token.version == room.version
-- 旧 token 即使签名有效、activeStart/activeEnd 一致，也会被判定为已失效

ALTER TABLE rooms ADD COLUMN qr_code_version BIGINT NOT NULL DEFAULT 1;
