package com.homektv.repo;

import com.homektv.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoomRepository extends JpaRepository<Room, UUID> {

    Optional<Room> findByDeviceId(String deviceId);

    List<Room> findByStatus(Room.RoomStatus status);

    List<Room> findByStatusIn(List<Room.RoomStatus> statuses);

    /** 查询 APPROVED 且 activeEnd 已过期的房间（定时任务移到 IDLE） */
    @Query("SELECT r FROM Room r WHERE r.status = 'APPROVED' AND r.activeEnd IS NOT NULL AND r.activeEnd < :now")
    List<Room> findApprovedExpired(@Param("now") LocalDateTime now);

    /** 查询 IDLE 且 activeStart 已到达的房间（定时任务升为 APPROVED） */
    @Query("SELECT r FROM Room r WHERE r.status = 'IDLE' AND r.activeStart IS NOT NULL AND r.activeStart <= :now")
    List<Room> findIdleReady(@Param("now") LocalDateTime now);

    boolean existsByDeviceId(String deviceId);

    List<Room> findAllByDeviceId(String deviceId);

    @Modifying
    @Query("DELETE FROM Room r WHERE r.deviceId = :deviceId")
    int deleteAllByDeviceId(@Param("deviceId") String deviceId);
}
