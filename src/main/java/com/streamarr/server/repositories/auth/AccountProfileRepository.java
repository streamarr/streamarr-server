package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AccountProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Extends bare {@code Repository} on purpose — inheriting {@code JpaRepository} would add blind
 * {@code save}/{@code delete}, bypassing the conditional operations that report their outcome.
 */
public interface AccountProfileRepository
    extends Repository<AccountProfile, UUID>, AccountProfileRepositoryCustom {

  Optional<AccountProfile> findByAccountIdAndProfileId(UUID accountId, UUID profileId);

  Optional<AccountProfile> findByAccountIdAndHouseholdIdAndProfileId(
      UUID accountId, UUID householdId, UUID profileId);

  List<AccountProfile> findByAccountIdAndHouseholdId(UUID accountId, UUID householdId);

  List<AccountProfile> findAll();
}
