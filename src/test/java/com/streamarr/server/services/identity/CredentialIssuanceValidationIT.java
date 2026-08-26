package com.streamarr.server.services.identity;

import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static com.streamarr.server.support.OutcomeTestSupport.rejectionOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationCommand;
import com.streamarr.server.support.AuthTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Credential Issuance Validation Integration Tests")
class CredentialIssuanceValidationIT extends AbstractIntegrationTest {

  @Autowired private CredentialIssuanceService credentialIssuanceService;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileManagerRepository managerRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;

  private AuthTestSupport.TestIdentity issuer;

  @AfterEach
  void tearDown() {
    invitationRepository.deleteAll();
    if (issuer != null) {
      authTestSupport.deleteIdentity(issuer);
    }
  }

  @Test
  @DisplayName("Should reject a restricted local manager when a restricted invitation is issued")
  void shouldRejectRestrictedLocalManagerWhenRestrictedInvitationIsIssued() {
    issuer = authTestSupport.createAdminIdentity();
    var restrictedManagerId = createRestrictedMemberManagedByIssuer();

    var outcome =
        credentialIssuanceService.issueAccountInvitation(
            authTestSupport.identityOf(issuer),
            supervisedInvitation().localManagerAccountId(restrictedManagerId).build());

    assertThat(rejectionOf(outcome)).isInstanceOf(InvitationRejections.LocalManagerNotFound.class);
    assertThat(invitationRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should accept a Kid invitation when the local manager is an unrestricted HouseholdAdmin")
  void shouldAcceptKidInvitationWhenLocalManagerIsUnrestrictedHouseholdAdmin() {
    issuer = authTestSupport.createAdminIdentity();

    var issued =
        accepted(
            credentialIssuanceService.issueAccountInvitation(
                authTestSupport.identityOf(issuer),
                supervisedInvitation().localManagerAccountId(issuer.account().getId()).build()));

    assertThat(invitationRepository.findById(issued.invitation().getId()).orElseThrow())
        .satisfies(
            invitation -> {
              assertThat(invitation.getStatus()).isEqualTo(AccountInvitationStatus.PENDING);
              assertThat(invitation.getProfileKind()).isEqualTo(ProfileKind.KID);
              assertThat(invitation.getLocalManagerAccountId()).isEqualTo(issuer.account().getId());
            });
  }

  @Test
  @DisplayName(
      "Should accept an Adult invitation with a rating ceiling when the local manager is eligible")
  void shouldAcceptAdultInvitationWithRatingCeilingWhenLocalManagerIsEligible() {
    issuer = authTestSupport.createAdminIdentity();

    var issued =
        accepted(
            credentialIssuanceService.issueAccountInvitation(
                authTestSupport.identityOf(issuer),
                supervisedInvitation()
                    .profileKind(ProfileKind.ADULT)
                    .maximumAllowedRatingAge(12)
                    .localManagerAccountId(issuer.account().getId())
                    .build()));

    assertThat(invitationRepository.findById(issued.invitation().getId()).orElseThrow())
        .satisfies(
            invitation -> {
              assertThat(invitation.getProfileKind()).isEqualTo(ProfileKind.ADULT);
              assertThat(invitation.getMaximumAllowedRatingAge()).isEqualTo(12);
              assertThat(invitation.getLocalManagerAccountId()).isEqualTo(issuer.account().getId());
            });
  }

  @Test
  @DisplayName("Should reject a Member-role local manager when a restricted invitation is issued")
  void shouldRejectMemberRoleLocalManagerWhenRestrictedInvitationIsIssued() {
    issuer = authTestSupport.createAdminIdentity();
    var memberId = createMember(ProfileFixture.defaultProfileBuilder()).getId();

    var outcome =
        credentialIssuanceService.issueAccountInvitation(
            authTestSupport.identityOf(issuer),
            supervisedInvitation().localManagerAccountId(memberId).build());

    assertThat(rejectionOf(outcome)).isInstanceOf(InvitationRejections.LocalManagerNotFound.class);
    assertThat(invitationRepository.findAll()).isEmpty();
  }

  /** A Kid invitation into the issuer's Household; the caller names the local manager. */
  private IssueInvitationCommand.IssueInvitationCommandBuilder supervisedInvitation() {
    return IssueInvitationCommand.builder()
        .recipientEmail("supervised@example.com")
        .householdId(issuer.household().getId())
        .householdRole(HouseholdRole.MEMBER)
        .profileName("Supervised")
        .profileKind(ProfileKind.KID);
  }

  private UUID createRestrictedMemberManagedByIssuer() {
    return transactionTemplate.execute(
        _ -> {
          var member = createMember(ProfileFixture.kidProfileBuilder());
          managerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(issuer.account().getId())
                  .profileId(member.getPersonalProfileId())
                  .build());
          return member.getId();
        });
  }

  /** A Member of the issuer's Household whose Personal Profile is the given one. */
  private UserAccount createMember(Profile.ProfileBuilder<?, ?> personalProfile) {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  personalProfile.householdId(issuer.household().getId()).build());
          var account =
              userAccountRepository.saveAndFlush(
                  AccountFixture.defaultAccountBuilder()
                      .householdId(issuer.household().getId())
                      .householdRole(HouseholdRole.MEMBER)
                      .personalProfileId(profile.getId())
                      .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(issuer.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          return account;
        });
  }
}
