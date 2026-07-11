package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeHouseholdMembershipRepository extends FakeJpaRepository<HouseholdMembership>
    implements HouseholdMembershipRepository {

  @Override
  public void grantMembership(HouseholdMembership membership) {
    save(membership);
  }

  @Override
  public boolean changeRole(HouseholdMembership membership) {
    return findByAccountIdAndHouseholdId(membership.getAccountId(), membership.getHouseholdId())
        .map(
            existing -> {
              existing.setHouseholdRole(membership.getHouseholdRole());
              return true;
            })
        .orElse(false);
  }

  @Override
  public boolean revokeMembership(UUID accountId, UUID householdId) {
    return findByAccountIdAndHouseholdId(accountId, householdId)
        .map(
            membership -> {
              delete(membership);
              return true;
            })
        .orElse(false);
  }

  @Override
  public List<HouseholdMembership> findByAccountId(UUID accountId) {
    return database.values().stream()
        .filter(membership -> accountId.equals(membership.getAccountId()))
        .toList();
  }

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
