package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.HouseholdMembership;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Extends bare {@code Repository} on purpose — inheriting {@code JpaRepository} would add blind
 * {@code save}/{@code delete}, bypassing the conditional operations that report their outcome.
 */
public interface HouseholdMembershipRepository
    extends Repository<HouseholdMembership, UUID>, HouseholdMembershipRepositoryCustom {

  Optional<HouseholdMembership> findById(UUID id);

  Optional<HouseholdMembership> findByAccountIdAndHouseholdId(UUID accountId, UUID householdId);

  List<HouseholdMembership> findByAccountId(UUID accountId);
}
