package com.streamarr.server.graphql.mutation.managers;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.identity.ManagerRejections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Every service rejection maps to exactly its schema error type. */
@Tag("UnitTest")
@DisplayName("Manager Errors Tests")
class ManagerErrorsTest {

  @Test
  @DisplayName(
      "Should describe the eligible Profile manager requirement without implementation detail")
  void shouldDescribeEligibleProfileManagerRequirementWithoutImplementationDetail() {
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
    assertThat(ManagerErrors.toInviteError(new ManagerRejections.RecipientNotFound()))
        .isInstanceOf(AccountNotFoundError.class);
    assertThat(ManagerErrors.toInviteError(new ManagerRejections.RecipientNotEligible()))
        .isInstanceOf(ProfileManagerNotEligibleError.class);
    assertThat(ManagerErrors.toInviteError(new ManagerRejections.AlreadyManager()))
        .isInstanceOf(AlreadyManagerError.class);
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
    assertThat(ManagerErrors.toDeclineError(new ManagerRejections.ManagerInvitationNotFound()))
        .isInstanceOf(ManagerInvitationNotFoundError.class);
  }

  @Test
  @DisplayName("Should point at recipientAccountId when an invitation recipient is invalid")
  void shouldPointAtRecipientAccountIdWhenInvitationRecipientIsInvalid() {
    assertThat(ManagerErrors.toInviteError(new ManagerRejections.RecipientNotFound()))
        .isInstanceOfSatisfying(
            AccountNotFoundError.class,
            error -> assertThat(error.inputPath()).containsExactly("recipientAccountId"));
    assertThat(ManagerErrors.toInviteError(new ManagerRejections.RecipientNotEligible()))
        .isInstanceOfSatisfying(
            ProfileManagerNotEligibleError.class,
            error -> assertThat(error.inputPath()).containsExactly("recipientAccountId"));
    assertThat(ManagerErrors.toInviteError(new ManagerRejections.AlreadyManager()))
        .isInstanceOfSatisfying(
            AlreadyManagerError.class,
            error -> assertThat(error.inputPath()).containsExactly("recipientAccountId"));
  }

  @Test
  @DisplayName("Should point at code when acceptance is rejected")
  void shouldPointAtCodeWhenAcceptanceIsRejected() {
    assertThat(ManagerErrors.toAcceptError(new ManagerRejections.RecipientNotEligible()))
        .isInstanceOfSatisfying(
            ProfileManagerNotEligibleError.class,
            error -> assertThat(error.inputPath()).containsExactly("code"));
    assertThat(ManagerErrors.toAcceptError(new ManagerRejections.AlreadyManager()))
        .isInstanceOfSatisfying(
            AlreadyManagerError.class,
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
    assertThat(ManagerErrors.toGrantOverrideError(new ManagerRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
    assertThat(ManagerErrors.toGrantOverrideError(new ManagerRejections.ReasonRequired()))
        .isInstanceOf(ReasonRequiredError.class);
    assertThat(ManagerErrors.toGrantOverrideError(new ManagerRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
    assertThat(ManagerErrors.toGrantOverrideError(new ManagerRejections.RecipientNotFound()))
        .isInstanceOf(AccountNotFoundError.class);
    assertThat(ManagerErrors.toGrantOverrideError(new ManagerRejections.RecipientNotEligible()))
        .isInstanceOf(ProfileManagerNotEligibleError.class);
    assertThat(ManagerErrors.toGrantOverrideError(new ManagerRejections.AlreadyManager()))
        .isInstanceOf(AlreadyManagerError.class);
    assertThat(ManagerErrors.toRemoveOverrideError(new ManagerRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
    assertThat(ManagerErrors.toRemoveOverrideError(new ManagerRejections.ReasonRequired()))
        .isInstanceOf(ReasonRequiredError.class);
    assertThat(
            ManagerErrors.toRemoveOverrideError(new ManagerRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
    assertThat(ManagerErrors.toRemoveOverrideError(new ManagerRejections.NotAManager()))
        .isInstanceOf(NotAManagerError.class);
    assertThat(ManagerErrors.toRemoveOverrideError(new ManagerRejections.EligibleManagerRequired()))
        .isInstanceOf(ProfileRequiresEligibleManagerError.class);
  }
}
