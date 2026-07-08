package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("IntegrationTest")
@DisplayName("Account Profile Repository Integration Tests")
class AccountProfileRepositoryIT extends AbstractIntegrationTest {

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private HouseholdRepository householdRepository;

  @Autowired private HouseholdMembershipRepository householdMembershipRepository;

  @Autowired private ProfileRepository profileRepository;

  @Autowired private AccountProfileRepository accountProfileRepository;

  @Test
  @DisplayName("Should bump membership version when profile link revoked")
  void shouldBumpMembershipVersionWhenProfileLinkRevoked() {
    var seeded = seedMembershipWithUnlinkedProfile();

    accountProfileRepository.linkProfile(seeded.link());

    assertThat(membershipVersionOf(seeded.membership())).isEqualTo(1L);

    var revoked = accountProfileRepository.revokeProfileLink(seeded.link());

    assertThat(revoked).isTrue();
    assertThat(membershipVersionOf(seeded.membership())).isEqualTo(2L);
    assertThat(accountProfileRepository.findAll())
        .noneMatch(remaining -> seeded.link().getProfileId().equals(remaining.getProfileId()));
  }

  @Test
  @DisplayName("Should not bump membership version when revoking absent link")
  void shouldNotBumpMembershipVersionWhenRevokingAbsentLink() {
    var seeded = seedMembershipWithUnlinkedProfile();

    var revoked = accountProfileRepository.revokeProfileLink(seeded.link());

    assertThat(revoked).isFalse();
    assertThat(membershipVersionOf(seeded.membership())).isZero();
  }

  @Test
  @DisplayName("Should not bump membership version when duplicate link rejected")
  void shouldNotBumpMembershipVersionWhenDuplicateLinkRejected() {
    var seeded = seedMembershipWithUnlinkedProfile();
    var link = seeded.link();
    accountProfileRepository.linkProfile(link);

    assertThatThrownBy(() -> accountProfileRepository.linkProfile(link))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(membershipVersionOf(seeded.membership())).isEqualTo(1L);
  }

  @Test
  @DisplayName("Should advance membership audit timestamp when version bumped")
  void shouldAdvanceMembershipAuditTimestampWhenVersionBumped() {
    var seeded = seedMembershipWithUnlinkedProfile();
    var savedAt = seeded.membership().getLastModifiedOn();

    accountProfileRepository.linkProfile(seeded.link());

    var reloaded =
        householdMembershipRepository.findById(seeded.membership().getId()).orElseThrow();
    assertThat(reloaded.getLastModifiedOn()).isAfter(savedAt);
  }

  private record SeededMembership(HouseholdMembership membership, AccountProfile link) {}

  private SeededMembership seedMembershipWithUnlinkedProfile() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var membership =
        householdMembershipRepository.save(
            HouseholdMembership.builder()
                .accountId(account.getId())
                .householdId(household.getId())
                .householdRole(HouseholdRole.OWNER)
                .build());
    var profile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    var link =
        AccountProfile.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .profileId(profile.getId())
            .build();
    return new SeededMembership(membership, link);
  }

  private long membershipVersionOf(HouseholdMembership membership) {
    return householdMembershipRepository
        .findById(membership.getId())
        .orElseThrow()
        .getMembershipVersion();
  }
}
