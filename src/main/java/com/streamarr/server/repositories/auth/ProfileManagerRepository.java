package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ProfileManagerRepository
    extends JpaRepository<ProfileManager, UUID>, ProfileManagerRepositoryCustom {

  boolean existsByAccountIdAndProfileId(UUID accountId, UUID profileId);

  @Lock(LockModeType.PESSIMISTIC_READ)
  @Transactional
  Optional<ProfileManager> findByAccountIdAndProfileId(UUID accountId, UUID profileId);

  List<ProfileManager> findByProfileId(UUID profileId);
}
