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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("IntegrationTest")
@DisplayName("Household Membership Repository Integration Tests")
class HouseholdMembershipRepositoryIT extends AbstractIntegrationTest {

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private HouseholdRepository householdRepository;

  @Autowired private HouseholdMembershipRepository membershipRepository;

  @Autowired private ProfileRepository profileRepository;

  @Autowired private AccountProfileRepository accountProfileRepository;

  @Test
  @DisplayName("Should persist membership when granted")
  void shouldPersistMembershipWhenGranted() {
    var membership = newMembership(HouseholdRole.MEMBER);

    membershipRepository.grantMembership(membership);

    assertThat(
            membershipRepository.findByAccountIdAndHouseholdId(
                membership.getAccountId(), membership.getHouseholdId()))
        .hasValueSatisfying(
            persisted -> assertThat(persisted.getHouseholdRole()).isEqualTo(HouseholdRole.MEMBER));
  }

  @Test
  @DisplayName("Should reject duplicate membership when already granted")
  void shouldRejectDuplicateMembershipWhenAlreadyGranted() {
    var membership = newMembership(HouseholdRole.MEMBER);
    membershipRepository.grantMembership(membership);

    assertThatThrownBy(
            () ->
                membershipRepository.grantMembership(
                    HouseholdMembership.builder()
                        .accountId(membership.getAccountId())
                        .householdId(membership.getHouseholdId())
                        .householdRole(HouseholdRole.PARENT)
                        .build()))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(
            membershipRepository.findByAccountIdAndHouseholdId(
                membership.getAccountId(), membership.getHouseholdId()))
        .hasValueSatisfying(
            persisted -> assertThat(persisted.getHouseholdRole()).isEqualTo(HouseholdRole.MEMBER));
  }

  @Test
  @DisplayName("Should persist role when membership role changed")
  void shouldPersistRoleWhenMembershipRoleChanged() {
    var membership = newMembership(HouseholdRole.MEMBER);
    membershipRepository.grantMembership(membership);

    assertThat(
            membershipRepository.changeRole(
                HouseholdMembership.builder()
                    .accountId(membership.getAccountId())
                    .householdId(membership.getHouseholdId())
                    .householdRole(HouseholdRole.PARENT)
                    .build()))
        .isTrue();
    assertThat(
            membershipRepository.findByAccountIdAndHouseholdId(
                membership.getAccountId(), membership.getHouseholdId()))
        .hasValueSatisfying(
            persisted -> assertThat(persisted.getHouseholdRole()).isEqualTo(HouseholdRole.PARENT));
  }

  @Test
  @DisplayName("Should return false when changing role for absent membership")
  void shouldReturnFalseWhenChangingRoleForAbsentMembership() {
    var membership = newMembership(HouseholdRole.PARENT);

    assertThat(membershipRepository.changeRole(membership)).isFalse();
    assertThat(
            membershipRepository.findByAccountIdAndHouseholdId(
                membership.getAccountId(), membership.getHouseholdId()))
        .isEmpty();
  }

  @Test
  @DisplayName("Should remove membership when revoked")
  void shouldRemoveMembershipWhenRevoked() {
    var membership = newMembership(HouseholdRole.MEMBER);
    membershipRepository.grantMembership(membership);

    assertThat(
            membershipRepository.revokeMembership(
                membership.getAccountId(), membership.getHouseholdId()))
        .isTrue();
    assertThat(
            membershipRepository.findByAccountIdAndHouseholdId(
                membership.getAccountId(), membership.getHouseholdId()))
        .isEmpty();
  }

  @Test
  @DisplayName("Should return false when revoking absent membership")
  void shouldReturnFalseWhenRevokingAbsentMembership() {
    var membership = newMembership(HouseholdRole.MEMBER);

    assertThat(
            membershipRepository.revokeMembership(
                membership.getAccountId(), membership.getHouseholdId()))
        .isFalse();
  }

  @Test
  @DisplayName("Should allow membership to be regranted after revocation")
  void shouldAllowMembershipToBeRegrantedAfterRevocation() {
    var membership = newMembership(HouseholdRole.MEMBER);
    membershipRepository.grantMembership(membership);
    assertThat(
            membershipRepository.revokeMembership(
                membership.getAccountId(), membership.getHouseholdId()))
        .isTrue();

    membershipRepository.grantMembership(
        HouseholdMembership.builder()
            .accountId(membership.getAccountId())
            .householdId(membership.getHouseholdId())
            .householdRole(HouseholdRole.PARENT)
            .build());

    assertThat(membershipRepository.findByAccountId(membership.getAccountId()))
        .singleElement()
        .satisfies(
            persisted -> assertThat(persisted.getHouseholdRole()).isEqualTo(HouseholdRole.PARENT));
  }

  @Test
  @DisplayName("Should match one legal ordering when membership grant and revocation race")
  void shouldMatchOneLegalOrderingWhenMembershipGrantAndRevocationRace() throws Exception {
    var membership = newMembership(HouseholdRole.MEMBER);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);

    boolean grantCompleted;
    boolean revoked;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var grant =
          executor.submit(
              () -> {
                awaitRace(ready, start);
                membershipRepository.grantMembership(membership);
                return true;
              });
      var revoke =
          executor.submit(
              () -> {
                awaitRace(ready, start);
                return membershipRepository.revokeMembership(
                    membership.getAccountId(), membership.getHouseholdId());
              });

      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      grantCompleted = grant.get(10, TimeUnit.SECONDS);
      revoked = revoke.get(10, TimeUnit.SECONDS);
    }

    var membershipPresent =
        membershipRepository
            .findByAccountIdAndHouseholdId(membership.getAccountId(), membership.getHouseholdId())
            .isPresent();
    assertThat(new GrantRevocationOutcome(grantCompleted, revoked, membershipPresent))
        .isIn(
            new GrantRevocationOutcome(true, false, true),
            new GrantRevocationOutcome(true, true, false));
  }

  @Test
  @DisplayName(
      "Should not resurrect membership or leave profile link when role change and revocation race")
  void shouldNotResurrectMembershipOrLeaveProfileLinkWhenRoleChangeAndRevocationRace()
      throws Exception {
    var membership = newMembership(HouseholdRole.MEMBER);
    membershipRepository.grantMembership(membership);
    var profile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder()
                .householdId(membership.getHouseholdId())
                .build());
    var link =
        AccountProfile.builder()
            .accountId(membership.getAccountId())
            .householdId(membership.getHouseholdId())
            .profileId(profile.getId())
            .build();
    accountProfileRepository.linkProfile(link);
    var roleChange =
        HouseholdMembership.builder()
            .accountId(membership.getAccountId())
            .householdId(membership.getHouseholdId())
            .householdRole(HouseholdRole.PARENT)
            .build();
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);

    boolean roleChanged;
    boolean revoked;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var changeRole =
          executor.submit(
              () -> {
                awaitRace(ready, start);
                return membershipRepository.changeRole(roleChange);
              });
      var revoke =
          executor.submit(
              () -> {
                awaitRace(ready, start);
                return membershipRepository.revokeMembership(
                    membership.getAccountId(), membership.getHouseholdId());
              });

      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      roleChanged = changeRole.get(10, TimeUnit.SECONDS);
      revoked = revoke.get(10, TimeUnit.SECONDS);
    }

    assertThat(new RoleChangeRevocationOutcome(roleChanged, revoked))
        .isIn(
            new RoleChangeRevocationOutcome(true, true),
            new RoleChangeRevocationOutcome(false, true));
    assertThat(
            membershipRepository.findByAccountIdAndHouseholdId(
                membership.getAccountId(), membership.getHouseholdId()))
        .isEmpty();
    assertThat(
            accountProfileRepository.findByAccountIdAndHouseholdIdAndProfileId(
                link.getAccountId(), link.getHouseholdId(), link.getProfileId()))
        .isEmpty();
  }

  private static void awaitRace(CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("race did not start before the timeout");
    }
  }

  private HouseholdMembership newMembership(HouseholdRole householdRole) {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    return HouseholdMembership.builder()
        .accountId(account.getId())
        .householdId(household.getId())
        .householdRole(householdRole)
        .build();
  }

  private record GrantRevocationOutcome(
      boolean grantCompleted, boolean revoked, boolean membershipPresent) {}

  private record RoleChangeRevocationOutcome(boolean roleChanged, boolean revoked) {}
}
