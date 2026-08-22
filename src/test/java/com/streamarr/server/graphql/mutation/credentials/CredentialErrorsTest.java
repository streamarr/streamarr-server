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
  @DisplayName("Should map the Profile path when CONNECT requires a Profile")
  void shouldMapProfilePathWhenConnectRequiresProfile() {
    assertThat(CredentialErrors.toIssueError(new InvitationRejections.ConnectProfileRequired()))
        .isInstanceOfSatisfying(
            ConnectProfileRequiredError.class,
            error -> assertThat(error.inputPath()).containsExactly("profileId"));
  }

  @Test
  @DisplayName("Should map the Profile path when the CONNECT Profile is not found")
  void shouldMapProfilePathWhenConnectProfileNotFound() {
    assertThat(CredentialErrors.toIssueError(new InvitationRejections.ConnectProfileNotFound()))
        .isInstanceOfSatisfying(
            ConnectProfileNotFoundError.class,
            error -> assertThat(error.inputPath()).containsExactly("profileId"));
  }

  @Test
  @DisplayName("Should map the Profile path when the CONNECT Profile is already linked")
  void shouldMapProfilePathWhenConnectProfileAlreadyLinked() {
    assertThat(CredentialErrors.toIssueError(new InvitationRejections.ProfileAlreadyLinked()))
        .isInstanceOfSatisfying(
            ProfileAlreadyLinkedError.class,
            error -> assertThat(error.inputPath()).containsExactly("profileId"));
  }

  @Test
  @DisplayName("Should map the Household path when the CONNECT Profile belongs elsewhere")
  void shouldMapHouseholdPathWhenConnectProfileBelongsElsewhere() {
    assertThat(CredentialErrors.toIssueError(new InvitationRejections.ProfileNotInHousehold()))
        .isInstanceOfSatisfying(
            ProfileNotInHouseholdError.class,
            error -> assertThat(error.inputPath()).containsExactly("householdId"));
  }

  @Test
  @DisplayName("Should map the reoffer path when a Household is not found")
  void shouldMapReofferPathWhenHouseholdNotFound() {
    assertThat(CredentialErrors.toIssueError(new InvitationRejections.ReofferHouseholdNotFound()))
        .isInstanceOfSatisfying(
            ReofferHouseholdNotFoundError.class,
            error -> assertThat(error.inputPath()).containsExactly("reofferHouseholdIds"));
  }

  @Test
  @DisplayName("Should map the reoffer path when the Profile is not shared")
  void shouldMapReofferPathWhenProfileNotShared() {
    assertThat(CredentialErrors.toIssueError(new InvitationRejections.ReofferHouseholdNotShared()))
        .isInstanceOfSatisfying(
            ReofferHouseholdNotSharedError.class,
            error -> assertThat(error.inputPath()).containsExactly("reofferHouseholdIds"));
  }

  @Test
  @DisplayName("Should map the cancel and reset rejections to their schema errors")
  void shouldMapCancelAndResetRejectionsToTheirSchemaErrors() {
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
