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
import com.homektv.security.QrTokenCipher;
import com.homektv.security.QrTokenCipher.QrTokenInvalidException;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;

import java.util.Map;
import java.util.Optional;
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
    private final QrTokenCipher qrCipher;

    public RoomService(RoomRepository roomRepository, RoomApplicationRepository applicationRepository,
                       RoomMemberRepository memberRepository, BlacklistRepository blacklistRepository,
                       WsBroadcaster broadcaster, QrTokenCipher qrCipher) {
        this.roomRepository = roomRepository;
        this.applicationRepository = applicationRepository;
        this.memberRepository = memberRepository;
        this.blacklistRepository = blacklistRepository;
        this.broadcaster = broadcaster;
        this.qrCipher = qrCipher;
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
                return ConnectResult.roomNotOpen(room);
            }
            log.info("设备 {} 连接成功，房间 {}", deviceId, room.getName());
            return ConnectResult.approved(room);
        }

        // 2b. 检查是否是空闲房间（等待管理员开启）
        Optional<Room> idleRoom = roomRepository.findByDeviceId(deviceId)
                .filter(r -> r.getStatus() == Room.RoomStatus.IDLE);
        if (idleRoom.isPresent()) {
            Room room = idleRoom.get();
            log.info("设备 {} 是空闲房间，等待管理员开启", deviceId);
            return ConnectResult.idle(room);
        }

        // 3. 检查是否有pending申请
        List<RoomApplication> pendingApps = applicationRepository.findByStatus(ApplicationStatus.PENDING)
                .stream().filter(a -> a.getDeviceId().equals(deviceId)).toList();

        if (!pendingApps.isEmpty()) {
            RoomApplication app = pendingApps.get(0);
            if (!app.isExpired()) {
                Room room = roomRepository.findByDeviceId(deviceId).orElse(null);
                log.info("设备 {} 有待处理申请", deviceId);
                return ConnectResult.pending(app, room);
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
                RoomApplication app = existingApps.get(0);
                Room room = roomRepository.findByDeviceId(deviceId).orElse(null);
                return ConnectResult.pending(app, room);
            }
            // Room 已存在但没有 pending 申请（之前被拒绝/关闭过）→ 重新创建申请并通知管理员
            Optional<Room> existingRoom = roomRepository.findByDeviceId(deviceId);
            if (existingRoom.isPresent()) {
                Room room = existingRoom.get();
                RoomApplication newApp = new RoomApplication();
                newApp.setDeviceId(deviceId);
                newApp.setRoomId(room.getId());
                newApp.setStatus(ApplicationStatus.PENDING);
                applicationRepository.save(newApp);
                notifyAdminNewApplication(room);
                log.info("设备 {} 重新发起申请，房间 {}", deviceId, room.getId());
                return ConnectResult.pending(newApp, room);
            }
            // 兜底：理论上不会走到这里
            return ConnectResult.blacklisted();
        }

        Room newRoom = new Room();
        newRoom.setDeviceId(deviceId);
        newRoom.setName(null); // null 表示未重命名，APK 显示默认标题 HOME KTV
        newRoom.setStatus(Room.RoomStatus.PENDING);
        roomRepository.save(newRoom);

        RoomApplication application = new RoomApplication();
        application.setDeviceId(deviceId);
        application.setRoomId(newRoom.getId());
        application.setStatus(ApplicationStatus.PENDING);
        applicationRepository.save(application);

        // 通知管理员有新申请
        notifyAdminNewApplication(newRoom);

        log.info("设备 {} 创建新申请，房间 {}", deviceId, newRoom.getId());
        return ConnectResult.pending(application, newRoom);
    }

    /**
     * 审批申请
     */
    @Transactional
    public boolean approveApplication(UUID applicationId) {
        log.info("approveApplication 开始: {}", applicationId);
        Optional<RoomApplication> optApp = applicationRepository.findById(applicationId);
        if (optApp.isEmpty()) {
            log.warn("approveApplication: 申请不存在 {}", applicationId);
            return false;
        }

        RoomApplication app = optApp.get();
        log.info("approveApplication: 当前状态 {}", app.getStatus());
        if (app.getStatus() != ApplicationStatus.PENDING) return false;

        Optional<Room> optRoom = roomRepository.findById(app.getRoomId());
        if (optRoom.isEmpty()) {
            log.warn("approveApplication: 房间不存在 {}", app.getRoomId());
            return false;
        }

        Room room = optRoom.get();
        log.info("approveApplication: 准备更新 room {} device {}", room.getId(), room.getDeviceId());
        room.setStatus(Room.RoomStatus.APPROVED);
        generateQrCode(room);
        roomRepository.save(room);

        app.setStatus(ApplicationStatus.APPROVED);
        applicationRepository.save(app);

        // 通知TV端申请已批准（发 WS 用 try 包住，避免异常导致整个事务 rollback）
        try {
            log.info("approveApplication: 调用 notifyTvApproved");
            notifyTvApproved(room);
        } catch (Exception e) {
            log.warn("通知TV端失败，不影响审批结果: {}", e.getMessage());
        }

        // 通知管理员申请已被批准（刷新申请列表）
        try {
            broadcaster.broadcast(WsEvent.of(WsEvent.APPLICATION_APPROVED, Map.of(
                    "application_id", app.getId().toString(),
                    "room_id", room.getId().toString(),
                    "device_id", app.getDeviceId()
            )));
            broadcaster.broadcast(WsEvent.of(WsEvent.ROOM_LIST_UPDATED, Map.of()));
        } catch (Exception e) {
            log.warn("通知管理员失败，不影响审批结果: {}", e.getMessage());
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

        // 先发黑名单通知，再断开连接，最后刷新管理员列表
        broadcaster.broadcastToRoom(app.getRoomId().toString(), WsEvent.of(WsEvent.DEVICE_BLACKLISTED, Map.of(
                "device_id", app.getDeviceId(),
                "reason", "管理员拒绝: " + reason
        )));
        // 再断开该设备的 WS 连接（让其进入 onConnectionChanged(false) → HTTP 重新授权）
        broadcaster.disconnectDeviceById(app.getDeviceId());
        // 刷新管理员页面
        broadcaster.broadcast(WsEvent.of(WsEvent.ROOM_LIST_UPDATED, Map.of()));

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
        // null 或空字符串视为无重命名，清空名称
        room.setName(name != null && !name.isBlank() ? name : null);
        roomRepository.save(room);

        // 通知TV端名称变更
        broadcaster.broadcast(WsEvent.of(WsEvent.ROOM_NAME_CHANGED, Map.of(
                "room_id", room.getId().toString(),
                "name", room.getName() != null ? room.getName() : ""
        )));

        return true;
    }

    /**
     * 设置房间开放时间/二维码有效期（统一接口）。
     * APPROVED 房间：更新 activeStart/activeEnd，刷新二维码并广播。
     * IDLE 房间：更新 activeStart/activeEnd，若开始时间已到则自动升为 APPROVED。
     */
    @Transactional
    public boolean setRoomActiveTime(UUID roomId, LocalDateTime start, LocalDateTime end) {
        Optional<Room> optRoom = roomRepository.findById(roomId);
        if (optRoom.isEmpty()) return false;

        Room room = optRoom.get();
        room.setActiveStart(start);
        room.setActiveEnd(end);

        if (room.getStatus() == Room.RoomStatus.APPROVED) {
            roomRepository.save(room);
            generateQrCode(room);
            broadcaster.broadcast(WsEvent.of(WsEvent.ROOM_TIME_CHANGED, Map.of(
                    "room_id", room.getId().toString(),
                    "active_start", start != null ? start.toString() : "",
                    "active_end", end != null ? end.toString() : "",
                    "qr_code", room.getQrCode()
            )));
        } else {
            boolean shouldPromote = start == null || !start.isAfter(LocalDateTime.now());
            if (shouldPromote) {
                room.setStatus(Room.RoomStatus.APPROVED);
                generateQrCode(room);
                roomRepository.save(room);
                notifyTvApproved(room);
                broadcaster.broadcast(WsEvent.of(WsEvent.ROOM_PROMOTED, Map.of(
                        "room_id", room.getId().toString(),
                        "device_id", room.getDeviceId()
                )));
                // 刷新前端房间列表（空闲→已允许）
                broadcaster.broadcast(WsEvent.of(WsEvent.ROOM_LIST_UPDATED, Map.of()));
                log.info("空闲房间 {} 因开始时间已到，自动升为已允许", roomId);
            } else {
                roomRepository.save(room);
                // 刷新前端房间列表（显示新的开始时间）
                broadcaster.broadcast(WsEvent.of(WsEvent.ROOM_LIST_UPDATED, Map.of()));
            }
        }

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
     * 解散房间：APPROVED → IDLE，进入空闲列表（清空二维码让 TV 显示等待开房）。
     */
    @Transactional
    public boolean dissolveRoom(UUID roomId) {
        Optional<Room> optRoom = roomRepository.findById(roomId);
        if (optRoom.isEmpty()) return false;

        Room room = optRoom.get();
        if (room.getStatus() != Room.RoomStatus.APPROVED) return false;

        room.setStatus(Room.RoomStatus.IDLE);
        room.setQrCode(null);
        room.setActiveStart(null);
        room.setActiveEnd(null);
        roomRepository.save(room);

        // 直接通知目标 TV 进入空闲状态（带 room_id 让 TV 知道是哪个房间）
        broadcaster.broadcastToRoom(room.getId().toString(), WsEvent.of(WsEvent.DEVICE_IDLE, Map.of(
                "room_id", room.getId().toString(),
                "device_id", room.getDeviceId()
        )));
        // 刷新管理员页面
        broadcaster.broadcast(WsEvent.of(WsEvent.ROOM_LIST_UPDATED, Map.of()));

        log.info("房间 {}（deviceId={}）已解散，进入空闲状态", roomId, room.getDeviceId());
        return true;
    }

    /**
     * 关闭房间：删除授权，Room 状态改回 PENDING，TV 重新走申请流程。
     */
    @Transactional
    public boolean disableRoom(UUID roomId) {
        Optional<Room> optRoom = roomRepository.findById(roomId);
        if (optRoom.isEmpty()) return false;

        Room room = optRoom.get();
        if (room.getStatus() != Room.RoomStatus.APPROVED) return false;

        // 改为 REJECTED 加入黑名单，不在任何列表显示
        room.setStatus(Room.RoomStatus.REJECTED);
        room.setQrCode(null);
        room.setActiveStart(null);
        room.setActiveEnd(null);
        roomRepository.save(room);

        String deviceId = room.getDeviceId();
        addToBlacklist(deviceId, "管理员关闭房间");

        // 先发黑名单通知给 APK（触发弹窗），再发房间状态通知，最后刷新列表
        broadcaster.broadcastToRoom(room.getId().toString(), WsEvent.of(WsEvent.DEVICE_BLACKLISTED, Map.of(
                "device_id", deviceId,
                "reason", "管理员关闭房间"
        )));
        broadcaster.broadcast(WsEvent.of(WsEvent.ROOM_DISABLED, Map.of(
                "room_id", room.getId().toString(),
                "device_id", deviceId
        )));
        broadcaster.broadcast(WsEvent.of(WsEvent.ROOM_LIST_UPDATED, Map.of()));

        log.info("房间 {}（deviceId={}）已关闭授权并加入黑名单", roomId, deviceId);
        return true;
    }

    /**
     * 获取所有空闲房间
     */
    public List<Room> getIdleRooms() {
        return roomRepository.findByStatus(Room.RoomStatus.IDLE);
    }

    /**
     * 重新开启房间 / 提升空闲房间为已允许：IDLE → APPROVED，刷新二维码。
     */
    @Transactional
    public boolean enableRoom(UUID roomId) {
        Optional<Room> optRoom = roomRepository.findById(roomId);
        if (optRoom.isEmpty()) return false;

        Room room = optRoom.get();
        if (room.getStatus() != Room.RoomStatus.IDLE) return false;

        room.setStatus(Room.RoomStatus.APPROVED);
        generateQrCode(room);
        roomRepository.save(room);

        notifyTvApproved(room);
        log.info("空闲房间 {} 已开启", roomId);
        return true;
    }

    /**
     * 定时任务：检查 APPROVED 房间是否到期、IDLE 房间是否到开始时间。
     * 每 30 秒执行一次。
     */
    @Scheduled(fixedRate = 30000)
    @Transactional
    public void processRoomSchedules() {
        LocalDateTime now = LocalDateTime.now();

        // APPROVED 房间到期 → 移入 IDLE
        List<Room> expiredRooms = roomRepository.findApprovedExpired(now);
        for (Room room : expiredRooms) {
            room.setStatus(Room.RoomStatus.IDLE);
            roomRepository.save(room);
            // 直接通知目标 TV 进入空闲状态
            broadcaster.broadcastToRoom(room.getId().toString(), WsEvent.of(WsEvent.DEVICE_IDLE, Map.of(
                    "room_id", room.getId().toString(),
                    "device_id", room.getDeviceId()
            )));
            broadcaster.broadcast(WsEvent.of(WsEvent.ROOM_LIST_UPDATED, Map.of()));
            log.info("房间 {}（deviceId={}）已到期，自动移入空闲", room.getId(), room.getDeviceId());
        }

        // IDLE 房间到达开始时间 → 升为 APPROVED
        List<Room> readyRooms = roomRepository.findIdleReady(now);
        for (Room room : readyRooms) {
            room.setStatus(Room.RoomStatus.APPROVED);
            generateQrCode(room);
            roomRepository.save(room);
            notifyTvApproved(room);
            broadcaster.broadcast(WsEvent.of(WsEvent.ROOM_PROMOTED, Map.of(
                    "room_id", room.getId().toString(),
                    "device_id", room.getDeviceId()
            )));
            broadcaster.broadcast(WsEvent.of(WsEvent.ROOM_LIST_UPDATED, Map.of()));
            log.info("空闲房间 {} 到达开始时间，自动升为已允许", room.getId());
        }
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

        String qrCode = room.getQrCode() != null ? room.getQrCode() : "";

        // 通知TV端二维码已更新
        broadcaster.broadcast(WsEvent.of(WsEvent.QR_CODE_REFRESHED, Map.of(
                "room_id", room.getId().toString(),
                "qr_code", qrCode
        )));

        return qrCode;
    }

    /**
     * 移动端扫码加入房间
     */
    @Transactional
    public JoinResult joinRoom(String qrCode, String deviceId, String nickname) {
        // 解密二维码 token，提取房间 ID 并校验签名
        QrTokenCipher.Decoded decoded;
        try {
            decoded = qrCipher.decode(qrCode);
        } catch (QrTokenInvalidException e) {
            return JoinResult.invalid("二维码无效");
        }

        Optional<Room> optRoom = roomRepository.findById(UUID.fromString(decoded.roomId()));
        if (optRoom.isEmpty()) {
            return JoinResult.invalid("二维码无效");
        }

        Room room = optRoom.get();

        // 校验 QR 版本号：每次 generateQrCode 会自增，版本不匹配说明 QR 已刷新，旧 QR 失效
        long currentVersion = room.getQrCodeVersion() == null ? 1L : room.getQrCodeVersion();
        if (decoded.qrCodeVersion() != currentVersion) {
            return JoinResult.invalid("二维码已失效，请刷新后重试");
        }

        // 验证加密 token 中的有效期与房间当前开放时间一致（防历史 token 重放）
        long nowMs = System.currentTimeMillis();
        Long roomStartMs = room.getActiveStart() != null
                ? room.getActiveStart().toInstant(ZoneOffset.UTC).toEpochMilli() : null;
        Long roomEndMs = room.getActiveEnd() != null
                ? room.getActiveEnd().toInstant(ZoneOffset.UTC).toEpochMilli() : null;
        if (!Objects.equals(decoded.activeStartMs(), roomStartMs)
                || !Objects.equals(decoded.activeEndMs(), roomEndMs)) {
            return JoinResult.invalid("二维码已过期，请刷新后重试");
        }
        if (decoded.isExpired(nowMs)) {
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
        broadcaster.broadcast(WsEvent.of(WsEvent.MEMBER_JOINED, Map.of(
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

        // 通知TV端超时 + 管理员黑名单更新（try 包住避免 WS 异常导致事务 rollback）
        try {
            roomRepository.findById(app.getRoomId()).ifPresent(room -> {
                broadcaster.broadcast(WsEvent.of(WsEvent.APPLICATION_EXPIRED, Map.of(
                        "room_id", room.getId().toString(),
                        "device_id", app.getDeviceId(),
                        "application_id", app.getId().toString()
                )));
                // 通知管理员黑名单已更新
                broadcaster.broadcast(WsEvent.of(WsEvent.DEVICE_BLACKLISTED, Map.of(
                        "device_id", app.getDeviceId(),
                        "reason", "申请超时3分钟自动拉黑"
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
        // 加密的二维码 token：包含 roomId + QR 版本号 + 开放起止时间戳 + 随机 nonce + HMAC 签名
        // 每次刷新都会生成不同 nonce + 自增 qrCodeVersion，二维码内容也会变化
        Long startMs = room.getActiveStart() != null
                ? room.getActiveStart().toInstant(ZoneOffset.UTC).toEpochMilli()
                : null;
        Long endMs = room.getActiveEnd() != null
                ? room.getActiveEnd().toInstant(ZoneOffset.UTC).toEpochMilli()
                : null;
        // 版本号属于当前二维码本身；生成后再保存同一个版本，避免新二维码刚生成就被判定为旧码。
        // The stored version must match the token just generated; increment before encoding.
        long version = (room.getQrCodeVersion() == null ? 0L : room.getQrCodeVersion()) + 1L;
        String token = qrCipher.encode(room.getId().toString(), version, startMs, endMs);
        room.setQrCode(token);
        room.setQrCodeVersion(version);
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
        broadcaster.broadcast(WsEvent.of(WsEvent.NEW_ROOM_APPLICATION, Map.of(
                "room_id", room.getId().toString(),
                "device_id", room.getDeviceId(),
                "name", room.getName() != null ? room.getName() : "",
                "created_at", room.getCreatedAt().toString()
        )));
        log.info("已广播 new_room_application 事件，房间 {} 设备 {}", room.getId(), room.getDeviceId());
    }

    private void notifyTvApproved(Room room) {
        log.info("notifyTvApproved: 准备通知房间 {} device {}", room.getId(), room.getDeviceId());
        broadcaster.broadcastToRoom(room.getId().toString(), WsEvent.of(WsEvent.DEVICE_APPROVED, Map.of(
                "room_id", room.getId().toString(),
                "name", room.getName() != null ? room.getName() : "",
                "qr_code", room.getQrCode() != null ? room.getQrCode() : "",
                "active_start", room.getActiveStart() != null ? room.getActiveStart().toString() : "",
                "active_end", room.getActiveEnd() != null ? room.getActiveEnd().toString() : ""
        )));
    }

    // 结果类
    public static class ConnectResult {
        public final String status; // blacklisted, approved, pending, room_not_open, idle
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

        public static ConnectResult pending(RoomApplication application, Room room) {
            return new ConnectResult("pending", room, application);
        }

        public static ConnectResult roomNotOpen(Room room) {
            return new ConnectResult("room_not_open", room, null);
        }

        public static ConnectResult idle(Room room) {
            return new ConnectResult("idle", room, null);
        }

        public WsEvent toWsEvent() {
            return switch (status) {
                case "approved" -> WsEvent.of(WsEvent.DEVICE_APPROVED, Map.of(
                        "room_id", room.getId().toString(),
                        "name", room.getName() == null ? "" : room.getName(),
                        "qr_code", room.getQrCode() == null ? "" : room.getQrCode(),
                        "active_start", room.getActiveStart() != null ? room.getActiveStart().toString() : "",
                        "active_end", room.getActiveEnd() != null ? room.getActiveEnd().toString() : ""
                ));
                case "pending" -> WsEvent.of(WsEvent.DEVICE_PENDING, Map.of(
                        "application_id", application.getId().toString(),
                        "expired_at", application.getExpiredAt().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                ));
                case "idle" -> WsEvent.of(WsEvent.DEVICE_IDLE, Map.of());
                case "room_not_open" -> WsEvent.of(WsEvent.DEVICE_ROOM_NOT_OPEN, Map.of());
                default -> WsEvent.of(WsEvent.DEVICE_BLACKLISTED, Map.of());
            };
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
