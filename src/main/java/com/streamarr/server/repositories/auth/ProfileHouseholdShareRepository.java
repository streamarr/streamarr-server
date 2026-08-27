package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProfileHouseholdShareRepository
    extends JpaRepository<ProfileHouseholdShare, UUID>, ProfileHouseholdShareRepositoryCustom {

  Optional<ProfileHouseholdShare> findByProfileIdAndHouseholdIdAndStatus(
      UUID profileId, UUID householdId, ProfileShareStatus status);

  List<ProfileHouseholdShare> findByHouseholdIdAndStatus(
      UUID householdId, ProfileShareStatus status);

  List<ProfileHouseholdShare> findByProfileIdAndStatus(UUID profileId, ProfileShareStatus status);

  List<ProfileHouseholdShare> findByProfileId(UUID profileId);
}
