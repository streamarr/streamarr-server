package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProfileManagerRepository extends JpaRepository<ProfileManager, UUID> {

  boolean existsByAccountIdAndProfileId(UUID accountId, UUID profileId);

  List<ProfileManager> findByProfileId(UUID profileId);

  List<ProfileManager> findByAccountId(UUID accountId);

  Optional<ProfileManager> findByAccountIdAndProfileId(UUID accountId, UUID profileId);

  long countByProfileId(UUID profileId);
}
