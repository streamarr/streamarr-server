package com.streamarr.server.graphql.mutation.managers;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.identity.ManagerRejections;
import com.streamarr.server.services.identity.ProfileRejections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Every service rejection maps to exactly its schema error type. */
@Tag("UnitTest")
@DisplayName("Manager Errors Tests")
class ManagerErrorsTest {

  @Test
  @DisplayName(
      "Should describe the eligible Profile manager requirement without implementation detail when an eligible manager is required")
  void
      shouldDescribeEligibleProfileManagerRequirementWithoutImplementationDetailWhenEligibleManagerIsRequired() {
    var error = ManagerErrors.toRelinquishError(new ManagerRejections.EligibleManagerRequired());

    assertThat(error)
        .isInstanceOfSatisfying(
            ProfileRequiresEligibleManagerError.class,
            required ->
                assertThat(required.message())
                    .isEqualTo("The Profile needs an eligible Profile manager in its Household."));
  }

  @Test
  @DisplayName("Should map invitation rejections when converting to schema errors")
  void shouldMapInvitationRejectionsWhenConvertingToSchemaErrors() {
    assertThat(ManagerErrors.toInviteError(new ManagerRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
    assertThat(
            ManagerErrors.toInviteError(
                new ProfileRejections.ManagersOnly(
                    ProfileRejections.ManagersOnlyReason.NOT_RESTRICTED)))
        .isEqualTo(
            new ProfileManagersOnlyError(
                "The Profile is not restricted, so only its managers may do this.",
                ProfileRejections.ManagersOnlyReason.NOT_RESTRICTED));
    assertThat(ManagerErrors.toInviteError(new ManagerRejections.EmailRequired()))
        .isInstanceOf(EmailRequiredError.class);
    assertThat(ManagerErrors.toInviteError(new ManagerRejections.EmailInvalid()))
        .isInstanceOf(EmailInvalidError.class);
    assertThat(ManagerErrors.toInviteError(new ManagerRejections.ManagerInvitationLimitReached()))
        .isInstanceOf(ManagerInvitationLimitReachedError.class);
    assertThat(ManagerErrors.toCancelError(new ManagerRejections.ManagerInvitationNotFound()))
        .isInstanceOf(ManagerInvitationNotFoundError.class);
    assertThat(ManagerErrors.toCancelError(new ManagerRejections.InvitationNotPending()))
        .isInstanceOf(InvitationNotPendingError.class);
    assertThat(ManagerErrors.toAcceptError(new ManagerRejections.ManagerInvitationNotFound()))
        .isInstanceOf(ManagerInvitationNotFoundError.class);
    assertThat(ManagerErrors.toAcceptError(new ManagerRejections.RecipientNotEligible()))
        .isInstanceOf(ProfileManagerNotEligibleError.class);
    assertThat(ManagerErrors.toAcceptError(new ManagerRejections.AlreadyManager()))
        .isInstanceOf(AlreadyManagerError.class);
    assertThat(ManagerErrors.toAcceptError(new ManagerRejections.InvitationNotPending()))
        .isInstanceOf(InvitationNotPendingError.class);
    assertThat(ManagerErrors.toDeclineError(new ManagerRejections.ManagerInvitationNotFound()))
        .isInstanceOf(ManagerInvitationNotFoundError.class);
    assertThat(ManagerErrors.toDeclineError(new ManagerRejections.InvitationNotPending()))
        .isInstanceOf(InvitationNotPendingError.class);
    assertThat(ManagerErrors.toLookUpError(new ManagerRejections.ManagerInvitationNotFound()))
        .isInstanceOf(ManagerInvitationNotFoundError.class);
  }

  @Test
  @DisplayName("Should point at the email when an invitation's address is refused")
  void shouldPointAtTheEmailWhenAnInvitationsAddressIsRefused() {
    assertThat(ManagerErrors.toInviteError(new ManagerRejections.EmailRequired()))
        .isInstanceOfSatisfying(
            EmailRequiredError.class,
            error -> assertThat(error.inputPath()).containsExactly("email"));
    assertThat(ManagerErrors.toInviteError(new ManagerRejections.EmailInvalid()))
        .isInstanceOfSatisfying(
            EmailInvalidError.class,
            error -> assertThat(error.inputPath()).containsExactly("email"));
  }

  @Test
  @DisplayName(
      "Should point at the invitation id when a decision is refused and at the code when a lookup"
          + " is refused")
  void shouldPointAtTheInvitationIdWhenADecisionIsRefusedAndAtTheCodeWhenALookupIsRefused() {
    assertThat(ManagerErrors.toAcceptError(new ManagerRejections.RecipientNotEligible()))
        .isInstanceOfSatisfying(
            ProfileManagerNotEligibleError.class,
            error -> assertThat(error.inputPath()).containsExactly("invitationId"));
    assertThat(ManagerErrors.toAcceptError(new ManagerRejections.AlreadyManager()))
        .isInstanceOfSatisfying(
            AlreadyManagerError.class,
            error -> assertThat(error.inputPath()).containsExactly("invitationId"));
    assertThat(ManagerErrors.toDeclineError(new ManagerRejections.InvitationNotPending()))
        .isInstanceOfSatisfying(
            InvitationNotPendingError.class,
            error -> assertThat(error.inputPath()).containsExactly("invitationId"));
    assertThat(ManagerErrors.toLookUpError(new ManagerRejections.ManagerInvitationNotFound()))
        .isInstanceOfSatisfying(
            ManagerInvitationNotFoundError.class,
            error -> assertThat(error.inputPath()).containsExactly("code"));
  }

  @Test
  @DisplayName("Should map removal and override rejections when converting to schema errors")
  void shouldMapRemovalAndOverrideRejectionsWhenConvertingToSchemaErrors() {
    assertThat(ManagerErrors.toRelinquishError(new ManagerRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
    assertThat(ManagerErrors.toRelinquishError(new ManagerRejections.ManagementAlreadyRemoved()))
        .isInstanceOf(ManagementAlreadyRemovedError.class);
    assertThat(ManagerErrors.toRelinquishError(new ManagerRejections.EligibleManagerRequired()))
        .isInstanceOf(ProfileRequiresEligibleManagerError.class);
    assertThat(ManagerErrors.toRemoveError(new ManagerRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
    assertThat(ManagerErrors.toRemoveError(new ManagerRejections.NotAManager()))
        .isInstanceOf(NotAManagerError.class);
    assertThat(ManagerErrors.toRemoveError(new ManagerRejections.EligibleManagerRequired()))
        .isInstanceOf(ProfileRequiresEligibleManagerError.class);
    assertThat(ManagerErrors.toAdministrativelyGrantError(new ManagerRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
    assertThat(ManagerErrors.toAdministrativelyGrantError(new ManagerRejections.ReasonRequired()))
        .isInstanceOf(ReasonRequiredError.class);
    assertThat(
            ManagerErrors.toAdministrativelyGrantError(
                new ManagerRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
    assertThat(
            ManagerErrors.toAdministrativelyGrantError(new ManagerRejections.RecipientNotFound()))
        .isInstanceOf(AccountNotFoundError.class);
    assertThat(
            ManagerErrors.toAdministrativelyGrantError(
                new ManagerRejections.RecipientNotEligible()))
        .isInstanceOf(ProfileManagerNotEligibleError.class);
    assertThat(ManagerErrors.toAdministrativelyGrantError(new ManagerRejections.AlreadyManager()))
        .isInstanceOf(AlreadyManagerError.class);
    assertThat(ManagerErrors.toAdministrativelyRemoveError(new ManagerRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
    assertThat(ManagerErrors.toAdministrativelyRemoveError(new ManagerRejections.ReasonRequired()))
        .isInstanceOf(ReasonRequiredError.class);
    assertThat(
            ManagerErrors.toAdministrativelyRemoveError(
                new ManagerRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
    assertThat(ManagerErrors.toAdministrativelyRemoveError(new ManagerRejections.NotAManager()))
        .isInstanceOf(NotAManagerError.class);
    assertThat(
            ManagerErrors.toAdministrativelyRemoveError(
                new ManagerRejections.EligibleManagerRequired()))
        .isInstanceOf(ProfileRequiresEligibleManagerError.class);
  }
}
