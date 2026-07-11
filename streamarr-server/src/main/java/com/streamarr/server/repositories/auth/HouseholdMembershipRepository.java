package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.HouseholdMembership;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Deliberately narrowed so every grant, role change, and revocation uses an explicit contract. */
public interface HouseholdMembershipRepository
    extends Repository<HouseholdMembership, UUID>, HouseholdMembershipRepositoryCustom {

  Optional<HouseholdMembership> findById(UUID id);

  Optional<HouseholdMembership> findByAccountIdAndHouseholdId(UUID accountId, UUID householdId);

  List<HouseholdMembership> findByAccountId(UUID accountId);
}
