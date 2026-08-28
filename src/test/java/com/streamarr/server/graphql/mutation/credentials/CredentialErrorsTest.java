package com.streamarr.server.graphql.mutation.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.graphql.mutation.InputMutationError;
import com.streamarr.server.services.identity.CredentialRejections;
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
    assertThat(CredentialErrors.toIssueError(new CredentialRejections.EmailRequired()))
        .isInstanceOf(EmailRequiredError.class);
    assertThat(CredentialErrors.toIssueError(new CredentialRejections.EmailInvalid()))
        .isInstanceOf(EmailInvalidError.class);
    assertThat(CredentialErrors.toIssueError(new CredentialRejections.EmailAlreadyUsed()))
        .isInstanceOf(EmailAlreadyUsedError.class);
    assertThat(CredentialErrors.toIssueError(new CredentialRejections.ProfileNameRequired()))
        .isInstanceOf(ProfileNameRequiredError.class);
    assertThat(CredentialErrors.toIssueError(new CredentialRejections.ProfileNameTaken()))
        .isInstanceOf(ProfileNameTakenError.class);
    assertThat(CredentialErrors.toIssueError(new CredentialRejections.HouseholdNotFound()))
        .isInstanceOf(HouseholdNotFoundError.class);
    assertThat(CredentialErrors.toIssueError(new CredentialRejections.RestrictedFirstAccount()))
        .isInstanceOf(RestrictedFirstAccountError.class);
    assertThat(CredentialErrors.toIssueError(new CredentialRejections.RestrictedHouseholdAdmin()))
        .isInstanceOf(RestrictedHouseholdAdminError.class);
    assertThat(CredentialErrors.toIssueError(new CredentialRejections.LocalManagerRequired()))
        .isInstanceOf(EligibleProfileManagerRequiredError.class)
        .satisfies(
            error ->
                assertThat(((InputMutationError) error).inputPath())
                    .containsExactly("profileManagerAccountId"));
    assertThat(CredentialErrors.toIssueError(new CredentialRejections.ProfileManagerNotEligible()))
        .isInstanceOf(ProfileManagerNotEligibleError.class)
        .satisfies(
            error ->
                assertThat(((InputMutationError) error).inputPath())
                    .containsExactly("profileManagerAccountId"));
    assertThat(
            CredentialErrors.toIssueError(
                new CredentialRejections.MaximumAllowedRatingAgeInvalid()))
        .isInstanceOf(MaximumAllowedRatingAgeInvalidError.class);
  }

  @Test
  @DisplayName("Should map the Profile path when LINK requires a Profile")
  void shouldMapProfilePathWhenLinkRequiresProfile() {
    assertThat(CredentialErrors.toIssueError(new CredentialRejections.LinkProfileRequired()))
        .isInstanceOfSatisfying(
            LinkProfileRequiredError.class,
            error -> {
              assertThat(error.message())
                  .isEqualTo("Choose the existing Profile to link to the recipient's Account.");
              assertThat(error.inputPath()).containsExactly("profileId");
            });
  }

  @Test
  @DisplayName("Should map the Profile path when the LINK Profile is not found")
  void shouldMapProfilePathWhenLinkProfileNotFound() {
    assertThat(CredentialErrors.toIssueError(new CredentialRejections.LinkProfileNotFound()))
        .isInstanceOfSatisfying(
            LinkProfileNotFoundError.class,
            error -> assertThat(error.inputPath()).containsExactly("profileId"));
  }

  @Test
  @DisplayName("Should map the Profile path when the LINK Profile is already linked")
  void shouldMapProfilePathWhenLinkProfileAlreadyLinked() {
    assertThat(CredentialErrors.toIssueError(new CredentialRejections.ProfileAlreadyLinked()))
        .isInstanceOfSatisfying(
            ProfileAlreadyLinkedError.class,
            error -> assertThat(error.inputPath()).containsExactly("profileId"));
  }

  @Test
  @DisplayName("Should map the Household path when the LINK Profile belongs elsewhere")
  void shouldMapHouseholdPathWhenLinkProfileBelongsElsewhere() {
    assertThat(CredentialErrors.toIssueError(new CredentialRejections.ProfileNotInHousehold()))
        .isInstanceOfSatisfying(
            ProfileNotInHouseholdError.class,
            error -> {
              assertThat(error.message())
                  .isEqualTo("Choose the Household this Profile belongs to.");
              assertThat(error.inputPath()).containsExactly("householdId");
            });
  }

  @Test
  @DisplayName("Should map the reoffer path when a Household is not found")
  void shouldMapReofferPathWhenHouseholdNotFound() {
    assertThat(CredentialErrors.toIssueError(new CredentialRejections.ReofferHouseholdNotFound()))
        .isInstanceOfSatisfying(
            ReofferHouseholdNotFoundError.class,
            error -> assertThat(error.inputPath()).containsExactly("reofferHouseholdIds"));
  }

  @Test
  @DisplayName("Should explain the previous-share requirement when a Profile is not shared")
  void shouldExplainPreviousShareRequirementWhenProfileNotShared() {
    assertThat(CredentialErrors.toIssueError(new CredentialRejections.ReofferHouseholdNotShared()))
        .isInstanceOfSatisfying(
            ReofferHouseholdNotSharedError.class,
            error -> {
              assertThat(error.message())
                  .isEqualTo(
                      "Choose a Household where the Profile previously had an active share.");
              assertThat(error.inputPath()).containsExactly("reofferHouseholdIds");
            });
  }

  @Test
  @DisplayName("Should map the cancel and reset rejections to their schema errors")
  void shouldMapCancelAndResetRejectionsToTheirSchemaErrors() {
    assertThat(CredentialErrors.toCancelError(new CredentialRejections.InvitationNotPending()))
        .isInstanceOf(InvitationNotPendingError.class);
    assertThat(CredentialErrors.toIssueResetError(new CredentialRejections.AccountNotFound()))
        .isInstanceOf(AccountNotFoundError.class);
    assertThat(CredentialErrors.toIssueResetError(new CredentialRejections.ReasonRequired()))
        .isInstanceOf(ReasonRequiredError.class);
    assertThat(
            CredentialErrors.toIssueResetError(new CredentialRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
  }
}
