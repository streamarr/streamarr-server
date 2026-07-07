package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.repositories.auth.AccountProfileRepository;

public class FakeAccountProfileRepository extends FakeJpaRepository<AccountProfile>
    implements AccountProfileRepository {

  private final FakeHouseholdMembershipRepository membershipRepository;

  public FakeAccountProfileRepository(FakeHouseholdMembershipRepository membershipRepository) {
    this.membershipRepository = membershipRepository;
  }

  @Override
  public void linkProfile(AccountProfile link) {
    save(link);
    bumpMembershipVersion(link);
  }

  @Override
  public void revokeProfileLink(AccountProfile link) {
    var removed =
        database
            .entrySet()
            .removeIf(
                entry ->
                    link.getAccountId().equals(entry.getValue().getAccountId())
                        && link.getHouseholdId().equals(entry.getValue().getHouseholdId())
                        && link.getProfileId().equals(entry.getValue().getProfileId()));

    if (!removed) {
      return;
    }

    bumpMembershipVersion(link);
  }

  private void bumpMembershipVersion(AccountProfile link) {
    membershipRepository.database.values().stream()
        .filter(
            membership ->
                link.getAccountId().equals(membership.getAccountId())
                    && link.getHouseholdId().equals(membership.getHouseholdId()))
        .forEach(membership -> membership.setVersion(membership.getVersion() + 1));
  }
}
