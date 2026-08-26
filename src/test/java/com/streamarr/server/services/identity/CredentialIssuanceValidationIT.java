package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
  @Autowired private JwtDecoder jwtDecoder;
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
            identityOf(issuer),
            IssueInvitationCommand.builder()
                .recipientEmail("supervised@example.com")
                .householdId(issuer.household().getId())
                .householdRole(HouseholdRole.MEMBER)
                .profileName("Supervised")
                .profileKind(ProfileKind.KID)
                .localManagerAccountId(restrictedManagerId)
                .build());

    assertThat(rejectionOf(outcome)).isInstanceOf(InvitationRejections.LocalManagerNotFound.class);
    assertThat(invitationRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should reject a negative maximum allowed rating age when an invitation is issued")
  void shouldRejectNegativeMaximumAllowedRatingAgeWhenInvitationIsIssued() {
    issuer = authTestSupport.createAdminIdentity();

    var outcome =
        credentialIssuanceService.issueAccountInvitation(
            identityOf(issuer),
            IssueInvitationCommand.builder()
                .recipientEmail("negative-rating-age@example.com")
                .householdId(issuer.household().getId())
                .householdRole(HouseholdRole.MEMBER)
                .profileName("Negative Rating Age")
                .profileKind(ProfileKind.ADULT)
                .maximumAllowedRatingAge(-1)
                .localManagerAccountId(issuer.account().getId())
                .build());

    assertThat(rejectionOf(outcome))
        .isInstanceOf(InvitationRejections.MaximumAllowedRatingAgeInvalid.class);
    assertThat(invitationRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should reject a duplicate Household Profile name when an invitation is issued")
  void shouldRejectDuplicateHouseholdProfileNameWhenInvitationIsIssued() {
    issuer = authTestSupport.createAdminIdentity();

    var outcome =
        credentialIssuanceService.issueAccountInvitation(
            identityOf(issuer),
            IssueInvitationCommand.builder()
                .recipientEmail("duplicate-profile-name@example.com")
                .householdId(issuer.household().getId())
                .householdRole(HouseholdRole.MEMBER)
                .profileName(issuer.profile().getName())
                .profileKind(ProfileKind.ADULT)
                .build());

    assertThat(rejectionOf(outcome)).isInstanceOf(InvitationRejections.ProfileNameTaken.class);
    assertThat(invitationRepository.findAll()).isEmpty();
  }

  private UUID createRestrictedMemberManagedByIssuer() {
    return transactionTemplate.execute(
        _ -> {
          var personalProfile =
              profileRepository.saveAndFlush(
                  ProfileFixture.kidProfileBuilder()
                      .householdId(issuer.household().getId())
                      .build());
          var account =
              userAccountRepository.saveAndFlush(
                  AccountFixture.defaultAccountBuilder()
                      .householdId(issuer.household().getId())
                      .householdRole(HouseholdRole.MEMBER)
                      .personalProfileId(personalProfile.getId())
                      .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(personalProfile.getId())
                  .householdId(issuer.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          managerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(issuer.account().getId())
                  .profileId(personalProfile.getId())
                  .build());
          return account.getId();
        });
  }

  private AuthenticatedIdentity identityOf(AuthTestSupport.TestIdentity identity) {
    return AuthenticatedIdentity.fromJwt(
        jwtDecoder.decode(authTestSupport.accountBearer(identity)));
  }

  private static Object rejectionOf(Outcome<?, ?> outcome) {
    return switch (outcome) {
      case Outcome.Rejected<?, ?>(var rejections) -> rejections.getFirst();
      case Outcome.Accepted<?, ?> accepted ->
          throw new AssertionError("expected a rejection but got " + accepted);
    };
  }
}
