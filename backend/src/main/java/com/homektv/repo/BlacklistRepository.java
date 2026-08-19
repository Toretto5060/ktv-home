package com.homektv.repo;

import com.homektv.domain.Blacklist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface BlacklistRepository extends JpaRepository<Blacklist, Long> {

    Optional<Blacklist> findByDeviceId(String deviceId);

    boolean existsByDeviceId(String deviceId);

    void deleteByDeviceId(String deviceId);
}
