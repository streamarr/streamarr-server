package com.streamarr.server.graphql.mutation.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.identity.TransferRejections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Every service rejection maps to exactly its schema error type. */
@Tag("UnitTest")
@DisplayName("Lifecycle Errors Tests")
class LifecycleErrorsTest {

  @Test
  @DisplayName("Should map every transfer rejection to its schema error")
  void shouldMapEveryTransferRejectionToItsSchemaError() {
    assertThat(LifecycleErrors.toTransferAccountError(new TransferRejections.AccountNotFound()))
        .isInstanceOf(AccountNotFoundError.class);
    assertThat(LifecycleErrors.toTransferAccountError(new TransferRejections.HouseholdNotFound()))
        .isInstanceOf(HouseholdNotFoundError.class);
    assertThat(LifecycleErrors.toTransferAccountError(new TransferRejections.SameHousehold()))
        .isInstanceOf(SameHouseholdError.class);
    assertThat(LifecycleErrors.toTransferAccountError(new TransferRejections.FinalAccount()))
        .isInstanceOf(FinalAccountError.class);
    assertThat(LifecycleErrors.toTransferAccountError(new TransferRejections.LastHouseholdAdmin()))
        .isInstanceOf(LastHouseholdAdminError.class);
    assertThat(LifecycleErrors.toTransferAccountError(new TransferRejections.NoEligibleAdmin()))
        .isInstanceOf(NoEligibleAdminError.class);
    assertThat(LifecycleErrors.toTransferAccountError(new TransferRejections.NameConflict()))
        .isInstanceOf(ProfileNameTakenError.class);
    assertThat(LifecycleErrors.toTransferAccountError(new TransferRejections.AnchorRequired()))
        .isInstanceOf(HomeAnchorRequiredError.class);
    assertThat(
            LifecycleErrors.toTransferAccountError(new TransferRejections.RestrictedFirstAccount()))
        .isInstanceOf(RestrictedFirstAccountError.class);
    assertThat(LifecycleErrors.toTransferProfileError(new TransferRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
    assertThat(LifecycleErrors.toTransferProfileError(new TransferRejections.HouseholdNotFound()))
        .isInstanceOf(HouseholdNotFoundError.class);
    assertThat(LifecycleErrors.toTransferProfileError(new TransferRejections.SameHousehold()))
        .isInstanceOf(SameHouseholdError.class);
    assertThat(LifecycleErrors.toTransferProfileError(new TransferRejections.ProfileLinked()))
        .isInstanceOf(ProfileLinkedError.class);
    assertThat(
            LifecycleErrors.toTransferProfileError(new TransferRejections.LocalManagerRequired()))
        .isInstanceOf(LocalManagerRequiredError.class);
    assertThat(
            LifecycleErrors.toTransferProfileError(new TransferRejections.LocalManagerNotFound()))
        .isInstanceOf(LocalManagerNotFoundError.class);
    assertThat(
            LifecycleErrors.toTransferProfileError(
                new TransferRejections.ReplacementManagerNotEligible()))
        .isInstanceOf(ReplacementManagerNotEligibleError.class);
    assertThat(LifecycleErrors.toTransferProfileError(new TransferRejections.NameConflict()))
        .isInstanceOf(ProfileNameTakenError.class);
    assertThat(LifecycleErrors.toTransferProfileError(new TransferRejections.NoEligibleAdmin()))
        .isInstanceOf(NoEligibleAdminError.class);
  }

  @Test
  @DisplayName("Should map every deletion rejection to its schema error")
  void shouldMapEveryDeletionRejectionToItsSchemaError() {
    assertThat(LifecycleErrors.toDeleteAccountError(new TransferRejections.AccountNotFound()))
        .isInstanceOf(AccountNotFoundError.class);
    assertThat(LifecycleErrors.toDeleteAccountError(new TransferRejections.ReasonRequired()))
        .isInstanceOf(ReasonRequiredError.class);
    assertThat(
            LifecycleErrors.toDeleteAccountError(new TransferRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
    assertThat(LifecycleErrors.toDeleteAccountError(new TransferRejections.FinalAccount()))
        .isInstanceOf(FinalAccountError.class);
    assertThat(LifecycleErrors.toDeleteAccountError(new TransferRejections.LastHouseholdAdmin()))
        .isInstanceOf(LastHouseholdAdminError.class);
    assertThat(LifecycleErrors.toDeleteAccountError(new TransferRejections.LastServerAdmin()))
        .isInstanceOf(LastServerAdminError.class);
    assertThat(
            LifecycleErrors.toDeleteAccountError(
                new TransferRejections.ReplacementManagerRequired()))
        .isInstanceOf(ReplacementManagerRequiredError.class);
    assertThat(
            LifecycleErrors.toDeleteAccountError(
                new TransferRejections.ReplacementManagerNotFound()))
        .isInstanceOf(ReplacementManagerNotFoundError.class);
    assertThat(
            LifecycleErrors.toDeleteAccountError(
                new TransferRejections.ReplacementManagerNotEligible()))
        .isInstanceOf(ReplacementManagerNotEligibleError.class);
    assertThat(LifecycleErrors.toDeleteAccountError(new TransferRejections.AnchorRequired()))
        .isInstanceOf(HomeAnchorRequiredError.class);
    assertThat(LifecycleErrors.toDeleteAccountError(new TransferRejections.NoEligibleAdmin()))
        .isInstanceOf(NoEligibleAdminError.class);
    assertThat(
            LifecycleErrors.toDeleteMyAccountError(new TransferRejections.ConfirmationRequired()))
        .isInstanceOf(ConfirmationRequiredError.class);
    assertThat(
            LifecycleErrors.toDeleteMyAccountError(
                new TransferRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
    assertThat(LifecycleErrors.toDeleteMyAccountError(new TransferRejections.FinalAccount()))
        .isInstanceOf(FinalAccountError.class);
    assertThat(LifecycleErrors.toDeleteMyAccountError(new TransferRejections.LastHouseholdAdmin()))
        .isInstanceOf(LastHouseholdAdminError.class);
    assertThat(LifecycleErrors.toDeleteMyAccountError(new TransferRejections.LastServerAdmin()))
        .isInstanceOf(LastServerAdminError.class);
    assertThat(LifecycleErrors.toDeleteMyAccountError(new TransferRejections.AnchorRequired()))
        .isInstanceOf(HomeAnchorRequiredError.class);
    assertThat(LifecycleErrors.toDeleteMyAccountError(new TransferRejections.NoEligibleAdmin()))
        .isInstanceOf(NoEligibleAdminError.class);
    assertThat(LifecycleErrors.toForceDeleteProfileError(new TransferRejections.ProfileNotFound()))
        .isInstanceOf(ProfileNotFoundError.class);
    assertThat(LifecycleErrors.toForceDeleteProfileError(new TransferRejections.ReasonRequired()))
        .isInstanceOf(ReasonRequiredError.class);
    assertThat(
            LifecycleErrors.toForceDeleteProfileError(
                new TransferRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
    assertThat(LifecycleErrors.toForceDeleteProfileError(new TransferRejections.ProfileLinked()))
        .isInstanceOf(ProfileLinkedError.class);
  }

  @Test
  @DisplayName("Should map replacement eligibility to each mutation's input field")
  void shouldMapReplacementEligibilityToEachMutationsInputField() {
    var deleteError =
        (ReplacementManagerNotEligibleError)
            LifecycleErrors.toDeleteAccountError(
                new TransferRejections.ReplacementManagerNotEligible());
    var transferError =
        (ReplacementManagerNotEligibleError)
            LifecycleErrors.toTransferProfileError(
                new TransferRejections.ReplacementManagerNotEligible());

    assertThat(deleteError.inputPath()).containsExactly("replacementManagerAccountId");
    assertThat(transferError.inputPath()).containsExactly("localManagerAccountId");
  }
}
