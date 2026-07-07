package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;
import java.util.Optional;
import java.util.UUID;

public class FakeHouseholdMembershipRepository extends FakeJpaRepository<HouseholdMembership>
    implements HouseholdMembershipRepository {

  @Override
  public Optional<HouseholdMembership> findByAccountIdAndHouseholdId(
      UUID accountId, UUID householdId) {
    return database.values().stream()
        .filter(
            membership ->
                accountId.equals(membership.getAccountId())
                    && householdId.equals(membership.getHouseholdId()))
        .findFirst();
  }
}
