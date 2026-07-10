package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.auth.TokenVersionCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Household Membership Repository Integration Tests")
class HouseholdMembershipRepositoryIT extends AbstractIntegrationTest {

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private HouseholdRepository householdRepository;

  @Autowired private HouseholdMembershipRepository membershipRepository;

  @Autowired private ProfileRepository profileRepository;

  @Autowired private AccountProfileRepository accountProfileRepository;

  @Autowired private TokenVersionCache tokenVersionCache;

  @Test
  @DisplayName("Should advance version when membership revoked")
  void shouldAdvanceVersionWhenMembershipRevoked() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var membership =
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .householdRole(HouseholdRole.MEMBER)
            .build();
    var granted = membershipRepository.grantMembership(membership);

    var revoked =
        membershipRepository.revokeMembership(account.getId(), household.getId()).orElseThrow();

    assertThat(revoked.version()).isGreaterThan(granted.version());
    assertThat(
            membershipRepository.findByAccountIdAndHouseholdId(account.getId(), household.getId()))
        .isEmpty();
  }

  @Test
  @DisplayName("Should refresh warmed version cache when membership revoked")
  void shouldRefreshWarmedVersionCacheWhenMembershipRevoked() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var granted =
        membershipRepository.grantMembership(
            HouseholdMembership.builder()
                .accountId(account.getId())
                .householdId(household.getId())
                .householdRole(HouseholdRole.MEMBER)
                .build());
    assertThat(tokenVersionCache.membershipVersion(account.getId(), household.getId()))
        .contains(granted.version());

    var revoked =
        membershipRepository.revokeMembership(account.getId(), household.getId()).orElseThrow();

    assertThat(tokenVersionCache.membershipVersion(account.getId(), household.getId()))
        .contains(revoked.version());
  }

  @Test
  @DisplayName("Should advance revocation beyond profile grant and revoke")
  void shouldAdvanceRevocationBeyondProfileGrantAndRevoke() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    membershipRepository.grantMembership(
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .householdRole(HouseholdRole.MEMBER)
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
    accountProfileRepository.linkProfile(link);
    accountProfileRepository.revokeProfileLink(link);
    var versionAfterProfileChanges =
        membershipRepository
            .findByAccountIdAndHouseholdId(account.getId(), household.getId())
            .orElseThrow()
            .getMembershipVersion();

    var revoked =
        membershipRepository.revokeMembership(account.getId(), household.getId()).orElseThrow();

    assertThat(revoked.version()).isGreaterThan(versionAfterProfileChanges);
  }

  @Test
  @DisplayName("Should advance version when household role changed")
  void shouldAdvanceVersionWhenHouseholdRoleChanged() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var granted =
        membershipRepository.grantMembership(
            HouseholdMembership.builder()
                .accountId(account.getId())
                .householdId(household.getId())
                .householdRole(HouseholdRole.MEMBER)
                .build());

    var roleChanged =
        membershipRepository
            .changeRole(
                HouseholdMembership.builder()
                    .accountId(account.getId())
                    .householdId(household.getId())
                    .householdRole(HouseholdRole.PARENT)
                    .build())
            .orElseThrow();

    assertThat(roleChanged.version()).isGreaterThan(granted.version());
    assertThat(
            membershipRepository
                .findByAccountIdAndHouseholdId(account.getId(), household.getId())
                .orElseThrow()
                .getHouseholdRole())
        .isEqualTo(HouseholdRole.PARENT);
  }

  @Test
  @DisplayName("Should refresh warmed version cache when household role changed")
  void shouldRefreshWarmedVersionCacheWhenHouseholdRoleChanged() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var granted =
        membershipRepository.grantMembership(
            HouseholdMembership.builder()
                .accountId(account.getId())
                .householdId(household.getId())
                .householdRole(HouseholdRole.MEMBER)
                .build());
    assertThat(tokenVersionCache.membershipVersion(account.getId(), household.getId()))
        .contains(granted.version());

    var roleChanged =
        membershipRepository
            .changeRole(
                HouseholdMembership.builder()
                    .accountId(account.getId())
                    .householdId(household.getId())
                    .householdRole(HouseholdRole.PARENT)
                    .build())
            .orElseThrow();

    assertThat(tokenVersionCache.membershipVersion(account.getId(), household.getId()))
        .contains(roleChanged.version());
  }

  @Test
  @DisplayName("Should not reuse version when membership revoked and regranted")
  void shouldNotReuseVersionWhenMembershipRevokedAndRegranted() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var membership =
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .householdRole(HouseholdRole.MEMBER)
            .build();
    var firstGrant = membershipRepository.grantMembership(membership);
    assertThat(tokenVersionCache.membershipVersion(account.getId(), household.getId()))
        .contains(firstGrant.version());
    var revoked =
        membershipRepository.revokeMembership(account.getId(), household.getId()).orElseThrow();
    assertThat(tokenVersionCache.membershipVersion(account.getId(), household.getId()))
        .contains(revoked.version());

    var regranted =
        membershipRepository.grantMembership(
            HouseholdMembership.builder()
                .accountId(account.getId())
                .householdId(household.getId())
                .householdRole(HouseholdRole.MEMBER)
                .build());

    assertThat(revoked.version()).isGreaterThan(firstGrant.version());
    assertThat(regranted.version()).isGreaterThan(revoked.version());
    assertThat(tokenVersionCache.membershipVersion(account.getId(), household.getId()))
        .contains(regranted.version());
  }
}
