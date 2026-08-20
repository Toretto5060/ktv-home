package com.homektv.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "rooms")
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "device_id", nullable = false, unique = true)
    private String deviceId;

    @Column(nullable = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoomStatus status = RoomStatus.PENDING;

    @Column(name = "active_start")
    private LocalDateTime activeStart;

    @Column(name = "active_end")
    private LocalDateTime activeEnd;

    @Column(name = "qr_code")
    private String qrCode;

    @Column(name = "qr_code_version", nullable = false)
    private Long qrCodeVersion = 1L;

    @Column(name = "qr_expire_at")
    private LocalDateTime qrExpireAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum RoomStatus {
        PENDING,
        APPROVED,
        REJECTED,
        IDLE
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public RoomStatus getStatus() { return status; }
    public void setStatus(RoomStatus status) { this.status = status; }

    public LocalDateTime getActiveStart() { return activeStart; }
    public void setActiveStart(LocalDateTime activeStart) { this.activeStart = activeStart; }

    public LocalDateTime getActiveEnd() { return activeEnd; }
    public void setActiveEnd(LocalDateTime activeEnd) { this.activeEnd = activeEnd; }

    public String getQrCode() { return qrCode; }
    public void setQrCode(String qrCode) { this.qrCode = qrCode; }

    public Long getQrCodeVersion() { return qrCodeVersion; }
    public void setQrCodeVersion(Long qrCodeVersion) { this.qrCodeVersion = qrCodeVersion; }

    public LocalDateTime getQrExpireAt() { return qrExpireAt; }
    public void setQrExpireAt(LocalDateTime qrExpireAt) { this.qrExpireAt = qrExpireAt; }

    public boolean isIdle() { return status == RoomStatus.IDLE; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public boolean isInActiveTime() {
        LocalDateTime now = LocalDateTime.now();
        if (activeStart != null && now.isBefore(activeStart)) {
            return false;
        }
        if (activeEnd != null && now.isAfter(activeEnd)) {
            return false;
        }
        return true;
    }

    public boolean isQrValid() {
        if (qrCode == null || qrCode.isEmpty()) {
            return false;
        }
        if (qrExpireAt != null && LocalDateTime.now().isAfter(qrExpireAt)) {
            return false;
        }
        return true;
    }
}
