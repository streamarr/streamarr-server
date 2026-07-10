package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.MembershipVersionChange;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeHouseholdMembershipRepository extends FakeJpaRepository<HouseholdMembership>
    implements HouseholdMembershipRepository {

  private long nextVersion;

  @Override
  public MembershipVersionChange grantMembership(HouseholdMembership membership) {
    membership.setMembershipVersion(++nextVersion);
    save(membership);
    return changeFrom(membership);
  }

  @Override
  public Optional<MembershipVersionChange> changeRole(HouseholdMembership membership) {
    return findByAccountIdAndHouseholdId(membership.getAccountId(), membership.getHouseholdId())
        .map(
            existing -> {
              existing.setHouseholdRole(membership.getHouseholdRole());
              return bumpVersion(existing);
            });
  }

  @Override
  public Optional<MembershipVersionChange> revokeMembership(UUID accountId, UUID householdId) {
    return findByAccountIdAndHouseholdId(accountId, householdId)
        .map(
            membership -> {
              var change = bumpVersion(membership);
              delete(membership);
              return change;
            });
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

  MembershipVersionChange bumpVersion(HouseholdMembership membership) {
    membership.setMembershipVersion(++nextVersion);
    return changeFrom(membership);
  }

  private MembershipVersionChange changeFrom(HouseholdMembership membership) {
    return new MembershipVersionChange(
        membership.getAccountId(), membership.getHouseholdId(), membership.getMembershipVersion());
  }
}
