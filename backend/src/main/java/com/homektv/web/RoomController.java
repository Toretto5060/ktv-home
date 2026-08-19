package com.homektv.web;

import com.homektv.domain.Blacklist;
import com.homektv.domain.Room;
import com.homektv.domain.RoomApplication;
import com.homektv.domain.RoomMember;
import com.homektv.library.RoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    /**
     * 获取所有已批准的房间
     */
    @GetMapping
    public List<Room> getApprovedRooms() {
        return roomService.getApprovedRooms();
    }

    /**
     * 获取所有待审核申请
     */
    @GetMapping("/applications")
    public List<RoomApplication> getPendingApplications() {
        return roomService.getPendingApplications();
    }

    /**
     * 审批申请
     */
    @PostMapping("/applications/{id}/approve")
    public ResponseEntity<?> approveApplication(@PathVariable UUID id) {
        boolean success = roomService.approveApplication(id);
        return success ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /**
     * 拒绝申请
     */
    @PostMapping("/applications/{id}/reject")
    public ResponseEntity<?> rejectApplication(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "管理员拒绝") : "管理员拒绝";
        boolean success = roomService.rejectApplication(id, reason);
        return success ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /**
     * 倒计时归零时主动处理超时（H5 / TV 端调用，免去 1 分钟定时器延迟）。
     */
    @PostMapping("/applications/{id}/expire")
    public ResponseEntity<?> expireApplication(@PathVariable UUID id) {
        boolean processed = roomService.processExpiredApplicationById(id);
        return processed ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /**
     * 更新房间名称
     */
    @PutMapping("/{id}/name")
    public ResponseEntity<?> updateRoomName(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        String name = body.get("name");
        boolean success = roomService.updateRoomName(id, name);
        return success ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /**
     * 设置房间开放时间
     */
    @PutMapping("/{id}/schedule")
    public ResponseEntity<?> setRoomSchedule(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        LocalDateTime start = body.get("active_start") != null ? LocalDateTime.parse(body.get("active_start")) : null;
        LocalDateTime end = body.get("active_end") != null ? LocalDateTime.parse(body.get("active_end")) : null;
        boolean success = roomService.setRoomActiveTime(id, start, end);
        return success ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /**
     * 删除房间：将 Room 及对应的 Application 一起删除。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteRoom(@PathVariable UUID id) {
        boolean success = roomService.deleteRoom(id);
        return success ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /**
     * 重新开启房间
     */
    @PostMapping("/{id}/enable")
    public ResponseEntity<?> enableRoom(@PathVariable UUID id) {
        boolean success = roomService.enableRoom(id);
        return success ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /**
     * 关闭房间：删除 Room + Application，APK 会重新发起申请并显示"等待审批"蒙版。
     */
    @PostMapping("/{id}/disable")
    public ResponseEntity<?> disableRoom(@PathVariable UUID id) {
        boolean success = roomService.disableRoom(id);
        return success ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /**
     * 刷新二维码
     */
    @PostMapping("/{id}/refresh-qr")
    public ResponseEntity<?> refreshQrCode(@PathVariable UUID id) {
        String qrCode = roomService.refreshQrCode(id);
        return qrCode != null ? ResponseEntity.ok(Map.of("qr_code", qrCode)) : ResponseEntity.notFound().build();
    }

    /**
     * 获取房间成员
     */
    @GetMapping("/{id}/members")
    public List<RoomMember> getRoomMembers(@PathVariable UUID id) {
        return roomService.getRoomMembers(id);
    }

    /**
     * 移动端扫码加入房间
     */
    @PostMapping("/join")
    public ResponseEntity<?> joinRoom(@RequestBody Map<String, String> body) {
        String qrCode = body.get("qr_code");
        String deviceId = body.get("device_id");
        String nickname = body.get("nickname");

        if (qrCode == null || deviceId == null || nickname == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少必要参数"));
        }

        RoomService.JoinResult result = roomService.joinRoom(qrCode, deviceId, nickname);
        if (result.success) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "room_id", result.room.getId().toString(),
                    "room_name", result.room.getName(),
                    "is_new_member", result.isNewMember
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", result.message
            ));
        }
    }

    /**
     * 获取黑名单
     */
    @GetMapping("/blacklist")
    public List<Blacklist> getBlacklist() {
        return roomService.getBlacklist();
    }

    /**
     * 解除黑名单
     */
    @DeleteMapping("/blacklist/{deviceId}")
    public ResponseEntity<?> removeFromBlacklist(@PathVariable String deviceId) {
        boolean success = roomService.removeFromBlacklist(deviceId);
        return success ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }
}
