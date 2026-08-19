package com.homektv.repo;

import com.homektv.domain.RoomMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoomMemberRepository extends JpaRepository<RoomMember, UUID> {

    List<RoomMember> findByRoomId(UUID roomId);

    Optional<RoomMember> findByRoomIdAndDeviceId(UUID roomId, String deviceId);

    boolean existsByRoomIdAndDeviceId(UUID roomId, String deviceId);

    void deleteByRoomIdAndDeviceId(UUID roomId, String deviceId);

    long countByRoomId(UUID roomId);

    void deleteByRoomId(UUID roomId);
}
