package com.homektv.repo;

import com.homektv.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoomRepository extends JpaRepository<Room, UUID> {

    Optional<Room> findByDeviceId(String deviceId);

    List<Room> findByStatus(Room.RoomStatus status);

    List<Room> findByStatusIn(List<Room.RoomStatus> statuses);

    boolean existsByDeviceId(String deviceId);

    List<Room> findAllByDeviceId(String deviceId);

    @Modifying
    @Query("DELETE FROM Room r WHERE r.deviceId = :deviceId")
    int deleteAllByDeviceId(@Param("deviceId") String deviceId);
}
