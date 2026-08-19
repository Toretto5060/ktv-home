-- 房间管理相关表
-- V18 房间管理

-- 房间表
CREATE TABLE rooms (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    active_start TIMESTAMP,
    active_end TIMESTAMP,
    qr_code VARCHAR(255),
    qr_expire_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_rooms_status ON rooms(status);
CREATE INDEX idx_rooms_device_id ON rooms(device_id);

-- 黑名单表
CREATE TABLE blacklist (
    id BIGSERIAL PRIMARY KEY,
    device_id VARCHAR(255) NOT NULL UNIQUE,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_blacklist_device_id ON blacklist(device_id);

-- 房间申请记录表
CREATE TABLE room_applications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id VARCHAR(255) NOT NULL,
    room_id UUID REFERENCES rooms(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    expired_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_room_applications_status ON room_applications(status);
CREATE INDEX idx_room_applications_device_id ON room_applications(device_id);
CREATE INDEX idx_room_applications_expired_at ON room_applications(expired_at);

-- 房间成员表
CREATE TABLE room_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    device_id VARCHAR(255) NOT NULL,
    nickname VARCHAR(255) NOT NULL,
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(room_id, device_id)
);

CREATE INDEX idx_room_members_room_id ON room_members(room_id);
CREATE INDEX idx_room_members_device_id ON room_members(device_id);
