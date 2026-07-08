package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AccountProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Deliberately narrowed: every mutation must go through the invariant-preserving fragment methods,
 * which bump household_membership.membership_version in the same transaction. Inherited save/delete
 * would create or remove grants without invalidating issued tokens.
 */
public interface AccountProfileRepository
    extends Repository<AccountProfile, UUID>, AccountProfileRepositoryCustom {

  Optional<AccountProfile> findByAccountIdAndProfileId(UUID accountId, UUID profileId);

  Optional<AccountProfile> findByAccountIdAndHouseholdIdAndProfileId(
      UUID accountId, UUID householdId, UUID profileId);

  List<AccountProfile> findByAccountIdAndHouseholdId(UUID accountId, UUID householdId);

  List<AccountProfile> findAll();
}
