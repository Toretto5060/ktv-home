package com.homektv.library;

import com.homektv.domain.Blacklist;
import com.homektv.domain.Room;
import com.homektv.domain.RoomApplication;
import com.homektv.domain.RoomApplication.ApplicationStatus;
import com.homektv.domain.RoomMember;
import com.homektv.repo.BlacklistRepository;
import com.homektv.repo.RoomApplicationRepository;
import com.homektv.repo.RoomMemberRepository;
import com.homektv.repo.RoomRepository;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private final RoomRepository roomRepository;
    private final RoomApplicationRepository applicationRepository;
    private final RoomMemberRepository memberRepository;
    private final BlacklistRepository blacklistRepository;
    private final WsBroadcaster broadcaster;

    public RoomService(RoomRepository roomRepository, RoomApplicationRepository applicationRepository,
                       RoomMemberRepository memberRepository, BlacklistRepository blacklistRepository,
                       WsBroadcaster broadcaster) {
        this.roomRepository = roomRepository;
        this.applicationRepository = applicationRepository;
        this.memberRepository = memberRepository;
        this.blacklistRepository = blacklistRepository;
        this.broadcaster = broadcaster;
    }

    /**
     * 设备连接验证，返回验证结果
     */
    @Transactional
    public ConnectResult connect(String deviceId) {
        // 1. 检查黑名单
        if (blacklistRepository.existsByDeviceId(deviceId)) {
            log.info("设备 {} 在黑名单中，拒绝连接", deviceId);
            return ConnectResult.blacklisted();
        }

        // 2. 检查是否有已批准的房间
        // 注意：解除黑名单时 RoomService.removeFromBlacklist 已删除该 device 的所有
        // 历史 Room 行；processExpiredApplications 也会清理过期 Room 行。
        // 这里命中 APPROVED 即视为"当前有效房间"，无需做防御性额外判断。
        Optional<Room> approvedRoom = roomRepository.findByDeviceId(deviceId)
                .filter(r -> r.getStatus() == Room.RoomStatus.APPROVED);

        if (approvedRoom.isPresent()) {
            Room room = approvedRoom.get();
            // 检查房间是否在开放时间内
            if (!room.isInActiveTime()) {
                log.info("设备 {} 的房间 {} 未在开放时间内", deviceId, room.getId());
                return ConnectResult.roomNotOpen();
            }
            log.info("设备 {} 连接成功，房间 {}", deviceId, room.getName());
            return ConnectResult.approved(room);
        }

        // 3. 检查是否有pending申请
        List<RoomApplication> pendingApps = applicationRepository.findByStatus(ApplicationStatus.PENDING)
                .stream().filter(a -> a.getDeviceId().equals(deviceId)).toList();

        if (!pendingApps.isEmpty()) {
            RoomApplication app = pendingApps.get(0);
            if (!app.isExpired()) {
                log.info("设备 {} 有待处理申请", deviceId);
                return ConnectResult.pending(app);
            }
        }

        // 4. 创建新申请
        // 防并发重复插入：deviceId 在 rooms 表上唯一，但 findByDeviceId 在一个事务内
        // 可能看不到另一个事务刚 INSERT 的行。两个并发请求都会走到这里并尝试 save，
        // 导致 unique 冲突 500 → TV 端拿不到 pending response。
        // 预检 existsByDeviceId 能在同一事务内看到已存在的行（数据库会读最新已 commit 数据 + 当前事务内的 INSERT）。
        if (roomRepository.existsByDeviceId(deviceId)) {
            // 竞态：另一个事务刚刚创建了 Room / Application，递归复用之
            log.info("设备 {} 撞到并发创建，复用现有记录", deviceId);
            List<RoomApplication> existingApps = applicationRepository.findByStatus(ApplicationStatus.PENDING)
                    .stream().filter(a -> a.getDeviceId().equals(deviceId) && !a.isExpired()).toList();
            if (!existingApps.isEmpty()) {
                return ConnectResult.pending(existingApps.get(0));
            }
            // 极少见：Room 已存在但没 pending 申请（被拒绝过） → 视作 pending 但后台再次创建申请
            // 这种边界场景简化为返回 blacklisted 让管理员介入
            return ConnectResult.blacklisted();
        }

        Room newRoom = new Room();
        newRoom.setDeviceId(deviceId);
        newRoom.setName(deviceId);
        newRoom.setStatus(Room.RoomStatus.PENDING);
        roomRepository.save(newRoom);

        RoomApplication application = new RoomApplication();
        application.setDeviceId(deviceId);
        application.setRoomId(newRoom.getId());
        application.setStatus(ApplicationStatus.PENDING);
        applicationRepository.save(application);

        // 通知管理员有新申请
        notifyAdminNewApplication(newRoom);
        // 通知该设备：申请已创建，等待审批
        try {
            broadcaster.broadcastToDevice(deviceId, WsEvent.of("device_pending", Map.of(
                    "device_id", deviceId,
                    "application_id", application.getId().toString(),
                    "expired_at", application.getExpiredAt().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            )));
        } catch (Exception e) {
            log.debug("通知TV端申请状态失败，不影响申请结果: {}", e.getMessage());
        }

        log.info("设备 {} 创建新申请，房间 {}", deviceId, newRoom.getId());
        return ConnectResult.pending(application);
    }

    /**
     * 审批申请
     */
    @Transactional
    public boolean approveApplication(UUID applicationId) {
        Optional<RoomApplication> optApp = applicationRepository.findById(applicationId);
        if (optApp.isEmpty()) return false;

        RoomApplication app = optApp.get();
        if (app.getStatus() != ApplicationStatus.PENDING) return false;

        Optional<Room> optRoom = roomRepository.findById(app.getRoomId());
        if (optRoom.isEmpty()) return false;

        Room room = optRoom.get();
        room.setStatus(Room.RoomStatus.APPROVED);
        generateQrCode(room);
        roomRepository.save(room);

        app.setStatus(ApplicationStatus.APPROVED);
        applicationRepository.save(app);

        // 通知TV端申请已批准（发 WS 用 try 包住，避免异常导致整个事务 rollback）
        try {
            notifyTvApproved(room);
        } catch (Exception e) {
            log.warn("通知TV端失败，不影响审批结果: {}", e.getMessage());
        }

        log.info("申请 {} 已批准，房间 {}", applicationId, room.getName());
        return true;
    }

    /**
     * 拒绝申请
     */
    @Transactional
    public boolean rejectApplication(UUID applicationId, String reason) {
        Optional<RoomApplication> optApp = applicationRepository.findById(applicationId);
        if (optApp.isEmpty()) return false;

        RoomApplication app = optApp.get();
        if (app.getStatus() != ApplicationStatus.PENDING) return false;

        app.setStatus(ApplicationStatus.REJECTED);
        applicationRepository.save(app);

        // 加入黑名单
        addToBlacklist(app.getDeviceId(), "管理员拒绝: " + reason);

        // 先断开该设备的 WS 连接（让其进入 onConnectionChanged(false) → HTTP 重新授权），
        // 再发黑名单通知。
        broadcaster.disconnectDeviceById(app.getDeviceId());
        // 通知 TV 端被拒绝（避免 TV 端持续显示 pending 蒙版）
        // 用 try 包住避免 WS 异常导致整个事务 rollback
        try {
            broadcaster.broadcast(WsEvent.of("device_blacklisted", Map.of(
                    "device_id", app.getDeviceId(),
                    "reason", "管理员拒绝: " + reason
            )));
        } catch (Exception e) {
            log.warn("通知TV端失败，不影响拒绝结果: {}", e.getMessage());
        }

        log.info("申请 {} 已拒绝，设备 {} 加入黑名单", applicationId, app.getDeviceId());
        return true;
    }

    /**
     * 更新房间名称
     */
    @Transactional
    public boolean updateRoomName(UUID roomId, String name) {
        Optional<Room> optRoom = roomRepository.findById(roomId);
        if (optRoom.isEmpty()) return false;

        Room room = optRoom.get();
        room.setName(name != null && !name.isBlank() ? name : room.getDeviceId());
        roomRepository.save(room);

        // 通知TV端名称变更
        broadcaster.broadcast(WsEvent.of("room_name_changed", Map.of(
                "room_id", room.getId().toString(),
                "name", room.getName()
        )));

        return true;
    }

    /**
     * 设置房间开放时间
     */
    @Transactional
    public boolean setRoomActiveTime(UUID roomId, LocalDateTime start, LocalDateTime end) {
        Optional<Room> optRoom = roomRepository.findById(roomId);
        if (optRoom.isEmpty()) return false;

        Room room = optRoom.get();
        room.setActiveStart(start);
        room.setActiveEnd(end);
        roomRepository.save(room);

        // 刷新二维码
        generateQrCode(room);

        // 通知TV端时间变更
        broadcaster.broadcast(WsEvent.of("room_time_changed", Map.of(
                "room_id", room.getId().toString(),
                "active_start", start != null ? start.toString() : null,
                "active_end", end != null ? end.toString() : null,
                "qr_code", room.getQrCode()
        )));

        return true;
    }

    /**
     * 删除房间：将 Room 及对应的 RoomApplication 一起删除。
     * 删除后 TV 重连会走新申请流程（返回 pending 或自动创建申请）。
     */
    @Transactional
    public boolean deleteRoom(UUID roomId) {
        Optional<Room> optRoom = roomRepository.findById(roomId);
        if (optRoom.isEmpty()) return false;

        Room room = optRoom.get();
        String deviceId = room.getDeviceId();

        // 先删 Application（避免 FK 约束），再删 Room
        applicationRepository.deleteByDeviceId(deviceId);
        roomRepository.delete(room);

        log.info("删除房间 {}（deviceId={}）", roomId, deviceId);
        return true;
    }

    /**
     * 关闭房间：删除 Room + Application，通知 APK 重新发起申请。
     * APK 收到 device_pending 后会显示"等待审批"蒙版。
     */
    @Transactional
    public boolean disableRoom(UUID roomId) {
        Optional<Room> optRoom = roomRepository.findById(roomId);
        if (optRoom.isEmpty()) return false;

        Room room = optRoom.get();
        String deviceId = room.getDeviceId();

        applicationRepository.deleteByDeviceId(deviceId);
        roomRepository.delete(room);

        // 通知 APK：记录已删除，请重新发起申请（走 connect 逻辑 → 新 Room + 新 pending Application）
        try {
            broadcaster.broadcast(WsEvent.of("device_pending", Map.of(
                    "device_id", deviceId
            )));
        } catch (Exception e) {
            log.warn("通知TV端失败，不影响关闭结果: {}", e.getMessage());
        }

        log.info("关闭房间 {}（deviceId={}）", roomId, deviceId);
        return true;
    }

    /**
     * 重新开启房间
     */
    @Transactional
    public boolean enableRoom(UUID roomId) {
        Optional<Room> optRoom = roomRepository.findById(roomId);
        if (optRoom.isEmpty()) return false;

        Room room = optRoom.get();
        room.setStatus(Room.RoomStatus.APPROVED);
        generateQrCode(room);
        roomRepository.save(room);

        notifyTvApproved(room);
        return true;
    }

    /**
     * 手动刷新二维码
     */
    @Transactional
    public String refreshQrCode(UUID roomId) {
        Optional<Room> optRoom = roomRepository.findById(roomId);
        if (optRoom.isEmpty()) return null;

        Room room = optRoom.get();
        generateQrCode(room);
        roomRepository.save(room);

        // 通知TV端二维码已更新
        broadcaster.broadcast(WsEvent.of("qr_code_refreshed", Map.of(
                "room_id", room.getId().toString(),
                "qr_code", room.getQrCode()
        )));

        return room.getQrCode();
    }

    /**
     * 移动端扫码加入房间
     */
    @Transactional
    public JoinResult joinRoom(String qrCode, String deviceId, String nickname) {
        Optional<Room> optRoom = roomRepository.findById(UUID.fromString(qrCode));
        if (optRoom.isEmpty()) {
            return JoinResult.invalid("二维码无效");
        }

        Room room = optRoom.get();

        // 验证二维码是否匹配
        if (!room.getQrCode().equals(qrCode)) {
            return JoinResult.invalid("二维码已过期，请刷新后重试");
        }

        // 验证房间状态
        if (room.getStatus() != Room.RoomStatus.APPROVED) {
            return JoinResult.invalid("房间未开放");
        }

        // 验证开放时间
        if (!room.isInActiveTime()) {
            return JoinResult.invalid("房间暂未开放");
        }

        // 检查是否已加入
        Optional<RoomMember> existing = memberRepository.findByRoomIdAndDeviceId(room.getId(), deviceId);
        if (existing.isPresent()) {
            // 已加入，更新昵称
            RoomMember member = existing.get();
            member.setNickname(nickname);
            memberRepository.save(member);
            return JoinResult.success(room, member, false);
        }

        // 新加入
        RoomMember member = new RoomMember();
        member.setRoomId(room.getId());
        member.setDeviceId(deviceId);
        member.setNickname(nickname);
        memberRepository.save(member);

        // 通知TV端有人加入
        broadcaster.broadcast(WsEvent.of("member_joined", Map.of(
                "room_id", room.getId().toString(),
                "device_id", deviceId,
                "nickname", nickname,
                "member_count", memberRepository.countByRoomId(room.getId())
        )));

        return JoinResult.success(room, member, true);
    }

    /**
     * 获取房间成员列表
     */
    public List<RoomMember> getRoomMembers(UUID roomId) {
        return memberRepository.findByRoomId(roomId);
    }

    /**
     * 获取所有已批准房间
     */
    public List<Room> getApprovedRooms() {
        return roomRepository.findByStatus(Room.RoomStatus.APPROVED);
    }

    /**
     * 获取所有待审核申请
     */
    public List<RoomApplication> getPendingApplications() {
        return applicationRepository.findByStatus(ApplicationStatus.PENDING);
    }

    /**
     * 获取黑名单列表
     */
    public List<Blacklist> getBlacklist() {
        return blacklistRepository.findAll();
    }

    /**
     * 解除黑名单
     *
     * <p>仅删除 blacklist 行，让设备可以重新发起申请；同时清空该设备在 rooms
     * 表里的所有历史 Room 行，避免下一次的 connect() 因为历史残留 Room 命中 APPROVED
     * 分支直接放行、或撞上 rooms_device_id_key 唯一约束。
     *
     * <p>"解除黑名单" ≠ "直接允许"，设备必须重新走 pending 审批流程。
     */
    @Transactional
    public boolean removeFromBlacklist(String deviceId) {
        blacklistRepository.deleteByDeviceId(deviceId);
        roomRepository.deleteAllByDeviceId(deviceId);
        return true;
    }

    /**
     * 定时任务：处理过期的申请
     *
     * <p>把过期 PENDING 申请置 EXPIRED、拉黑设备；并清掉该 device 对应的 Room 行，
     * 避免 rooms_device_id_key 唯一约束阻止下一次重新申请。
     */
    @Scheduled(fixedRate = 60000) // 每分钟检查一次
    @Transactional
    public void processExpiredApplications() {
        List<RoomApplication> expiredApps = applicationRepository.findExpiredApplications(LocalDateTime.now());
        if (expiredApps.isEmpty()) {
            log.debug("定时检查过期申请：无过期");
            return;
        }
        log.info("定时检查发现 {} 条过期申请", expiredApps.size());
        for (RoomApplication app : expiredApps) {
            processSingleExpiredApplication(app);
        }
    }

    /**
     * 实时处理单个过期申请（不用等定时任务的 1 分钟延迟）。
     * H5 倒计时归零时 / TV 端发现 expiredAt 已过去时调用。
     */
    @Transactional
    public boolean processExpiredApplicationById(UUID applicationId) {
        Optional<RoomApplication> optApp = applicationRepository.findById(applicationId);
        if (optApp.isEmpty()) return false;
        RoomApplication app = optApp.get();
        if (app.getStatus() != ApplicationStatus.PENDING) return false;
        if (!app.isExpired()) return false; // 还没到期
        processSingleExpiredApplication(app);
        return true;
    }

    /** 实际处理单条过期申请（不加锁，外部调用方负责去重）。 */
    private void processSingleExpiredApplication(RoomApplication app) {
        app.setStatus(ApplicationStatus.EXPIRED);
        applicationRepository.save(app);

        // 自动加入黑名单
        addToBlacklist(app.getDeviceId(), "申请超时3分钟自动拉黑");

        // 通知TV端超时 + H5 刷新（try 包住避免 WS 异常导致事务 rollback）
        try {
            roomRepository.findById(app.getRoomId()).ifPresent(room -> {
                broadcaster.broadcast(WsEvent.of("application_expired", Map.of(
                        "room_id", room.getId().toString(),
                        "device_id", app.getDeviceId(),
                        "application_id", app.getId().toString()
                )));
            });
        } catch (Exception e) {
            log.warn("通知超时失败，不影响拉黑结果: {}", e.getMessage());
        }

        // 清理该 device 对应的 Room 行：避免 unique 冲突 + 数据库残留
        roomRepository.deleteAllByDeviceId(app.getDeviceId());

        log.info("申请 {} 已过期，设备 {} 自动拉黑", app.getId(), app.getDeviceId());
    }

    private void generateQrCode(Room room) {
        // 生成包含房间ID的二维码内容
        String qrContent = room.getId().toString();
        room.setQrCode(qrContent);
        // 管理员手动刷新或设置时间时设置过期时间（可设为30天后）
        room.setQrExpireAt(LocalDateTime.now().plusDays(30));
    }

    private void addToBlacklist(String deviceId, String reason) {
        if (!blacklistRepository.existsByDeviceId(deviceId)) {
            Blacklist blacklist = new Blacklist();
            blacklist.setDeviceId(deviceId);
            blacklist.setReason(reason);
            blacklistRepository.save(blacklist);
        }
    }

    private void notifyAdminNewApplication(Room room) {
        broadcaster.broadcast(WsEvent.of("new_room_application", Map.of(
                "room_id", room.getId().toString(),
                "device_id", room.getDeviceId(),
                "name", room.getName(),
                "created_at", room.getCreatedAt().toString()
        )));
    }

    private void notifyTvApproved(Room room) {
        broadcaster.broadcast(WsEvent.of("device_approved", Map.of(
                "room_id", room.getId().toString(),
                "name", room.getName(),
                "qr_code", room.getQrCode(),
                "active_start", room.getActiveStart() != null ? room.getActiveStart().toString() : null,
                "active_end", room.getActiveEnd() != null ? room.getActiveEnd().toString() : null
        )));
    }

    // 结果类
    public static class ConnectResult {
        public final String status; // blacklisted, approved, pending, room_not_open
        public final Room room;
        public final RoomApplication application;

        private ConnectResult(String status, Room room, RoomApplication application) {
            this.status = status;
            this.room = room;
            this.application = application;
        }

        public static ConnectResult blacklisted() {
            return new ConnectResult("blacklisted", null, null);
        }

        public static ConnectResult approved(Room room) {
            return new ConnectResult("approved", room, null);
        }

        public static ConnectResult pending(RoomApplication application) {
            return new ConnectResult("pending", null, application);
        }

        public static ConnectResult roomNotOpen() {
            return new ConnectResult("room_not_open", null, null);
        }
    }

    public static class JoinResult {
        public final boolean success;
        public final String message;
        public final Room room;
        public final RoomMember member;
        public final boolean isNewMember;

        private JoinResult(boolean success, String message, Room room, RoomMember member, boolean isNewMember) {
            this.success = success;
            this.message = message;
            this.room = room;
            this.member = member;
            this.isNewMember = isNewMember;
        }

        public static JoinResult invalid(String message) {
            return new JoinResult(false, message, null, null, false);
        }

        public static JoinResult success(Room room, RoomMember member, boolean isNewMember) {
            return new JoinResult(true, null, room, member, isNewMember);
        }
    }
}
