package com.homektv.repo;

import com.homektv.domain.RoomApplication;
import com.homektv.domain.RoomApplication.ApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface RoomApplicationRepository extends JpaRepository<RoomApplication, UUID> {

    List<RoomApplication> findByStatus(ApplicationStatus status);

    List<RoomApplication> findByDeviceId(String deviceId);

    void deleteByDeviceId(String deviceId);

    @Query("SELECT r FROM RoomApplication r WHERE r.status = 'PENDING' AND r.expiredAt < :now")
    List<RoomApplication> findExpiredApplications(LocalDateTime now);
}
