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
  @DisplayName("Should persist profile link when linked")
  void shouldPersistProfileLinkWhenLinked() {
    var link = newUnlinkedProfile();

    accountProfileRepository.linkProfile(link);

    assertThat(
            accountProfileRepository.findByAccountIdAndHouseholdIdAndProfileId(
                link.getAccountId(), link.getHouseholdId(), link.getProfileId()))
        .hasValueSatisfying(
            persisted -> {
              assertThat(persisted.getAccountId()).isEqualTo(link.getAccountId());
              assertThat(persisted.getHouseholdId()).isEqualTo(link.getHouseholdId());
              assertThat(persisted.getProfileId()).isEqualTo(link.getProfileId());
            });
  }

  @Test
  @DisplayName("Should remove profile link when revoked")
  void shouldRemoveProfileLinkWhenRevoked() {
    var link = newUnlinkedProfile();
    accountProfileRepository.linkProfile(link);

    assertThat(accountProfileRepository.revokeProfileLink(link)).isTrue();
    assertThat(
            accountProfileRepository.findByAccountIdAndHouseholdIdAndProfileId(
                link.getAccountId(), link.getHouseholdId(), link.getProfileId()))
        .isEmpty();
  }

  @Test
  @DisplayName("Should return false when revoking absent profile link")
  void shouldReturnFalseWhenRevokingAbsentProfileLink() {
    var link = newUnlinkedProfile();

    assertThat(accountProfileRepository.revokeProfileLink(link)).isFalse();
    assertThat(
            accountProfileRepository.findByAccountIdAndHouseholdIdAndProfileId(
                link.getAccountId(), link.getHouseholdId(), link.getProfileId()))
        .isEmpty();
  }

  @Test
  @DisplayName("Should reject duplicate profile link when already linked")
  void shouldRejectDuplicateProfileLinkWhenAlreadyLinked() {
    var link = newUnlinkedProfile();
    accountProfileRepository.linkProfile(link);

    assertThatThrownBy(() -> accountProfileRepository.linkProfile(link))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_account_profile_account_profile");
    assertThat(
            accountProfileRepository.findByAccountIdAndHouseholdId(
                link.getAccountId(), link.getHouseholdId()))
        .singleElement()
        .satisfies(
            persisted -> assertThat(persisted.getProfileId()).isEqualTo(link.getProfileId()));
  }

  private AccountProfile newUnlinkedProfile() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    householdMembershipRepository.grantMembership(
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .householdRole(HouseholdRole.OWNER)
            .build());
    var profile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    return AccountProfile.builder()
        .accountId(account.getId())
        .householdId(household.getId())
        .profileId(profile.getId())
        .build();
  }
}
