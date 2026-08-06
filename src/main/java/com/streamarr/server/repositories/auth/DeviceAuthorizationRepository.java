package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.DeviceAuthorization;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeviceAuthorizationRepository
    extends JpaRepository<DeviceAuthorization, UUID>, DeviceAuthorizationRepositoryCustom {

  Optional<DeviceAuthorization> findByUserCode(String userCode);
}
