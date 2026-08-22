package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationReoffer;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.fixtures.HouseholdFixture;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@Tag("IntegrationTest")
@DisplayName("Account Invitation Reoffer Repository Integration Tests")
@Transactional
class AccountInvitationReofferRepositoryIT extends AbstractIntegrationTest {

  @Autowired private HouseholdRepository householdRepository;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private AccountInvitationReofferRepository reofferRepository;

  @Test
  @DisplayName("Should reject a duplicate Household when saving invitation reoffers")
  void shouldRejectDuplicateHouseholdWhenSavingInvitationReoffers() {
    var household =
        householdRepository.saveAndFlush(HouseholdFixture.defaultHouseholdBuilder().build());
    var invitation =
        invitationRepository.saveAndFlush(
            AccountInvitation.builder()
                .recipientEmail("reoffer-constraint@example.com")
                .householdId(household.getId())
                .householdName(household.getName())
                .householdRole(HouseholdRole.MEMBER)
                .profileName("Joe")
                .profileKind(ProfileKind.ADULT)
                .expiresAt(Instant.now().plusSeconds(3600))
                .publicId("reoffer-constraint")
                .secretDigest(new byte[] {1})
                .build());
    reofferRepository.saveAndFlush(reoffer(invitation, household));

    assertThatThrownBy(() -> reofferRepository.saveAndFlush(reoffer(invitation, household)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("uq_account_invitation_reoffer_household");
  }

  private static AccountInvitationReoffer reoffer(
      AccountInvitation invitation, Household household) {
    return AccountInvitationReoffer.builder()
        .invitationId(invitation.getId())
        .householdId(household.getId())
        .householdName(household.getName())
        .build();
  }
}
