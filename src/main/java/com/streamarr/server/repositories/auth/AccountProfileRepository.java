package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AccountProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Deliberately narrowed: link creation and revocation use explicit operations whose outcomes are
 * visible to callers instead of generic inherited mutations.
 */
public interface AccountProfileRepository
    extends Repository<AccountProfile, UUID>, AccountProfileRepositoryCustom {

  Optional<AccountProfile> findByAccountIdAndProfileId(UUID accountId, UUID profileId);

  Optional<AccountProfile> findByAccountIdAndHouseholdIdAndProfileId(
      UUID accountId, UUID householdId, UUID profileId);

  List<AccountProfile> findByAccountIdAndHouseholdId(UUID accountId, UUID householdId);

  List<AccountProfile> findAll();
}
