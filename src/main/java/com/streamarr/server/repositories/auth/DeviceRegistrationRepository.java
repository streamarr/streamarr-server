package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeviceRegistrationRepository
    extends JpaRepository<DeviceRegistration, UUID>, DeviceRegistrationRepositoryCustom {

  List<DeviceRegistration> findByHouseholdIdAndStatus(
      UUID householdId, DeviceRegistrationStatus status);
}
