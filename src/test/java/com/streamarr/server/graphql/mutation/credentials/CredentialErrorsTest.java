package com.streamarr.server.graphql.mutation.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.identity.InvitationRejections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Every service rejection maps to exactly its schema error type. */
@Tag("UnitTest")
@DisplayName("Credential Errors Tests")
class CredentialErrorsTest {

  @Test
  @DisplayName("Should map every issuance rejection when it is converted to a schema error")
  void shouldMapEveryIssuanceRejectionWhenConvertedToSchemaError() {
    assertThat(CredentialErrors.toIssueError(new InvitationRejections.EmailRequired()))
        .isInstanceOf(EmailRequiredError.class);
    assertThat(CredentialErrors.toIssueError(new InvitationRejections.EmailAlreadyUsed()))
        .isInstanceOf(EmailAlreadyUsedError.class);
    assertThat(CredentialErrors.toIssueError(new InvitationRejections.ProfileNameRequired()))
        .isInstanceOf(ProfileNameRequiredError.class);
    assertThat(CredentialErrors.toIssueError(new InvitationRejections.HouseholdNotFound()))
        .isInstanceOf(HouseholdNotFoundError.class);
    assertThat(CredentialErrors.toIssueError(new InvitationRejections.RestrictedFirstAccount()))
        .isInstanceOf(RestrictedFirstAccountError.class);
    assertThat(CredentialErrors.toIssueError(new InvitationRejections.LocalManagerRequired()))
        .isInstanceOf(LocalManagerRequiredError.class);
    assertThat(CredentialErrors.toIssueError(new InvitationRejections.LocalManagerNotFound()))
        .isInstanceOf(LocalManagerNotFoundError.class);
  }

  @Test
  @DisplayName("Should map cancel and reset rejections when they are converted to schema errors")
  void shouldMapCancelAndResetRejectionsWhenConvertedToSchemaErrors() {
    assertThat(CredentialErrors.toCancelError(new InvitationRejections.InvitationNotPending()))
        .isInstanceOf(InvitationNotPendingError.class);
    assertThat(CredentialErrors.toIssueResetError(new InvitationRejections.AccountNotFound()))
        .isInstanceOf(AccountNotFoundError.class);
    assertThat(CredentialErrors.toIssueResetError(new InvitationRejections.ReasonRequired()))
        .isInstanceOf(ReasonRequiredError.class);
    assertThat(
            CredentialErrors.toIssueResetError(new InvitationRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
  }
}
