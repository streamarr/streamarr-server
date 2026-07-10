package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import java.util.Optional;
import java.util.UUID;

public class FakeAccountProfileRepository extends FakeJpaRepository<AccountProfile>
    implements AccountProfileRepository {

  private final FakeHouseholdMembershipRepository membershipRepository;

  public FakeAccountProfileRepository(FakeHouseholdMembershipRepository membershipRepository) {
    this.membershipRepository = membershipRepository;
  }

  @Override
  public Optional<AccountProfile> findByAccountIdAndHouseholdIdAndProfileId(
      UUID accountId, UUID householdId, UUID profileId) {
    return database.values().stream()
        .filter(
            link ->
                accountId.equals(link.getAccountId())
                    && householdId.equals(link.getHouseholdId())
                    && profileId.equals(link.getProfileId()))
        .findFirst();
  }

  @Override
  public java.util.List<AccountProfile> findByAccountIdAndHouseholdId(
      UUID accountId, UUID householdId) {
    return database.values().stream()
        .filter(
            link ->
                accountId.equals(link.getAccountId()) && householdId.equals(link.getHouseholdId()))
        .toList();
  }

  @Override
  public Optional<AccountProfile> findByAccountIdAndProfileId(UUID accountId, UUID profileId) {
    return database.values().stream()
        .filter(
            link -> accountId.equals(link.getAccountId()) && profileId.equals(link.getProfileId()))
        .findFirst();
  }

  @Override
  public void linkProfile(AccountProfile link) {
    save(link);
    bumpMembershipVersion(link);
  }

  @Override
  public boolean revokeProfileLink(AccountProfile link) {
    var removed =
        database
            .entrySet()
            .removeIf(
                entry ->
                    link.getAccountId().equals(entry.getValue().getAccountId())
                        && link.getHouseholdId().equals(entry.getValue().getHouseholdId())
                        && link.getProfileId().equals(entry.getValue().getProfileId()));

    if (!removed) {
      return false;
    }

    bumpMembershipVersion(link);
    return true;
  }

  private void bumpMembershipVersion(AccountProfile link) {
    membershipRepository.database.values().stream()
        .filter(
            membership ->
                link.getAccountId().equals(membership.getAccountId())
                    && link.getHouseholdId().equals(membership.getHouseholdId()))
        .forEach(membershipRepository::bumpVersion);
  }
}
