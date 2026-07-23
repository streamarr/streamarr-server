package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import java.util.UUID;
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

  @Test
  @DisplayName("Should persist membership grant")
  void shouldPersistMembershipGrant() {
    var membership = newMembership(HouseholdRole.MEMBER);

    membershipRepository.grantMembership(membership);

    assertThat(
            membershipRepository.findByAccountIdAndHouseholdId(
                membership.getAccountId(), membership.getHouseholdId()))
        .contains(membership);
  }

  @Test
  @DisplayName("Should change role when membership exists")
  void shouldChangeRoleWhenMembershipExists() {
    var membership = newMembership(HouseholdRole.MEMBER);
    membershipRepository.grantMembership(membership);

    var changed =
        membershipRepository.changeRole(
            HouseholdMembership.builder()
                .accountId(membership.getAccountId())
                .householdId(membership.getHouseholdId())
                .householdRole(HouseholdRole.PARENT)
                .build());

    assertThat(changed).isTrue();
    assertThat(
            membershipRepository
                .findByAccountIdAndHouseholdId(
                    membership.getAccountId(), membership.getHouseholdId())
                .orElseThrow()
                .getHouseholdRole())
        .isEqualTo(HouseholdRole.PARENT);
  }

  @Test
  @DisplayName("Should report false when changing absent membership")
  void shouldReportFalseWhenChangingAbsentMembership() {
    assertThat(
            membershipRepository.changeRole(
                HouseholdMembership.builder()
                    .accountId(UUID.randomUUID())
                    .householdId(UUID.randomUUID())
                    .householdRole(HouseholdRole.PARENT)
                    .build()))
        .isFalse();
  }

  @Test
  @DisplayName("Should remove membership when it exists")
  void shouldRemoveMembershipWhenItExists() {
    var membership = newMembership(HouseholdRole.MEMBER);
    membershipRepository.grantMembership(membership);

    var removed =
        membershipRepository.revokeMembership(
            membership.getAccountId(), membership.getHouseholdId());

    assertThat(removed).isTrue();
    assertThat(
            membershipRepository.findByAccountIdAndHouseholdId(
                membership.getAccountId(), membership.getHouseholdId()))
        .isEmpty();
    assertThat(
            membershipRepository.revokeMembership(
                membership.getAccountId(), membership.getHouseholdId()))
        .isFalse();
  }

  private HouseholdMembership newMembership(HouseholdRole role) {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    return HouseholdMembership.builder()
        .accountId(account.getId())
        .householdId(household.getId())
        .householdRole(role)
        .build();
  }
}
