package com.streamarr.server.graphql.mutation.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.identity.TransferRejections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every service rejection maps to exactly its schema error type. */
@Tag("UnitTest")
@DisplayName("Lifecycle Errors Tests")
class LifecycleErrorsTest {

  @Test
  @DisplayName(
      "Should omit implementation detail when the eligible Profile manager requirement is described")
  void shouldOmitImplementationDetailWhenEligibleProfileManagerRequirementIsDescribed() {
    var error =
        LifecycleErrors.toTransferAccountError(new TransferRejections.EligibleManagerRequired());

    assertThat(error)
        .isInstanceOfSatisfying(
            ProfileRequiresEligibleManagerError.class,
            required ->
                assertThat(required.message())
                    .isEqualTo("The Profile needs an eligible Profile manager in its Household."));
  }

  @Test
  @DisplayName("Should direct the mutation through its Account when a Profile is linked")
  void shouldDirectMutationThroughAccountWhenProfileIsLinked() {
    var error = LifecycleErrors.toTransferProfileError(new TransferRejections.ProfileLinked());

    assertThat(error)
        .isInstanceOfSatisfying(
            ProfileBelongsToAccountError.class,
            linked ->
                assertThat(linked.message())
                    .isEqualTo(
                        "This Profile belongs to an Account. Transfer or delete the Account instead."));
  }

  @Test
  @DisplayName("Should require an enabled ServerAdmin when the final ServerAdmin is changed")
  void shouldRequireEnabledServerAdminWhenFinalServerAdminIsChanged() {
    var error = LifecycleErrors.toDeleteAccountError(new TransferRejections.LastServerAdmin());

    assertThat(error)
        .isInstanceOfSatisfying(
            LastServerAdminError.class,
            last ->
                assertThat(last.message())
                    .isEqualTo("At least one enabled ServerAdmin must remain."));
  }

  @Test
  @DisplayName("Should require a HouseholdAdmin when a Household has Accounts")
  void shouldRequireHouseholdAdminWhenHouseholdHasAccounts() {
    var error = LifecycleErrors.toTransferAccountError(new TransferRejections.LastHouseholdAdmin());

    assertThat(error)
        .isInstanceOfSatisfying(
            LastHouseholdAdminError.class,
            last ->
                assertThat(last.message())
                    .isEqualTo("A Household with Accounts must keep at least one HouseholdAdmin."));
  }

  @Test
  @DisplayName("Should direct to Household deletion when the final Account is removed")
  void shouldDirectToHouseholdDeletionWhenFinalAccountIsRemoved() {
    var error = LifecycleErrors.toDeleteAccountError(new TransferRejections.FinalAccount());

    assertThat(error)
        .isInstanceOfSatisfying(
            LastHouseholdAccountError.class,
            last ->
                assertThat(last.message())
                    .isEqualTo("The last Account can be removed only by deleting the Household."));
  }

  @Test
  @DisplayName("Should ask to select a destination manager when one is required")
  void shouldAskToSelectDestinationManagerWhenOneIsRequired() {
    var error =
        LifecycleErrors.toTransferProfileError(new TransferRejections.LocalManagerRequired());

    assertThat(error)
        .isInstanceOfSatisfying(
            EligibleProfileManagerRequiredError.class,
            required ->
                assertThat(required.message())
                    .isEqualTo("Select an eligible Profile manager in the destination Household."));
  }

  @Test
  @DisplayName("Should explain the unrestricted first Account requirement in two sentences")
  void shouldExplainUnrestrictedFirstAccountRequirementInTwoSentences() {
    var error =
        LifecycleErrors.toTransferAccountError(new TransferRejections.RestrictedFirstAccount());

    assertThat(error)
        .isInstanceOfSatisfying(
            RestrictedFirstAccountError.class,
            restricted ->
                assertThat(restricted.message())
                    .isEqualTo(
                        "The first Account of an empty Household becomes HouseholdAdmin. It cannot be restricted."));
  }

  @Test
  @DisplayName("Should require selecting a replacement manager before keeping a Profile")
  void shouldRequireSelectingReplacementManagerBeforeKeepingProfile() {
    var error =
        LifecycleErrors.toDeleteAccountError(new TransferRejections.ReplacementManagerRequired());

    assertThat(error)
        .isInstanceOfSatisfying(
            ReplacementManagerRequiredError.class,
            required ->
                assertThat(required.message())
                    .isEqualTo(
                        "Keeping this Profile first requires selecting a replacement Profile manager."));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("transferErrorCases")
  @DisplayName("Should map to the expected schema error when an Account transfer is rejected")
  void shouldMapToExpectedSchemaErrorWhenAccountTransferIsRejected(
      String caseName, Supplier<Object> mapping, Class<?> expectedType) {
    assertThat(mapping.get()).isInstanceOf(expectedType);
  }

  private static Stream<Arguments> transferErrorCases() {
    return Stream.of(
        errorCase(
            "Account not found",
            () -> LifecycleErrors.toTransferAccountError(new TransferRejections.AccountNotFound()),
            AccountNotFoundError.class),
        errorCase(
            "Account destination not found",
            () ->
                LifecycleErrors.toTransferAccountError(new TransferRejections.HouseholdNotFound()),
            HouseholdNotFoundError.class),
        errorCase(
            "Account destination unchanged",
            () -> LifecycleErrors.toTransferAccountError(new TransferRejections.SameHousehold()),
            SameHouseholdError.class),
        errorCase(
            "Final Account",
            () -> LifecycleErrors.toTransferAccountError(new TransferRejections.FinalAccount()),
            LastHouseholdAccountError.class),
        errorCase(
            "Last HouseholdAdmin",
            () ->
                LifecycleErrors.toTransferAccountError(new TransferRejections.LastHouseholdAdmin()),
            LastHouseholdAdminError.class),
        errorCase(
            "No eligible HouseholdAdmin",
            () -> LifecycleErrors.toTransferAccountError(new TransferRejections.NoEligibleAdmin()),
            RestrictedProfileRequiresHouseholdAdminError.class),
        errorCase(
            "Account Profile name conflict",
            () -> LifecycleErrors.toTransferAccountError(new TransferRejections.NameConflict()),
            ProfileNameTakenError.class),
        errorCase(
            "Account Profile needs an eligible Profile manager",
            () ->
                LifecycleErrors.toTransferAccountError(
                    new TransferRejections.EligibleManagerRequired()),
            ProfileRequiresEligibleManagerError.class),
        errorCase(
            "Restricted first Account",
            () ->
                LifecycleErrors.toTransferAccountError(
                    new TransferRejections.RestrictedFirstAccount()),
            RestrictedFirstAccountError.class),
        errorCase(
            "Profile not found",
            () -> LifecycleErrors.toTransferProfileError(new TransferRejections.ProfileNotFound()),
            ProfileNotFoundError.class),
        errorCase(
            "Profile destination not found",
            () ->
                LifecycleErrors.toTransferProfileError(new TransferRejections.HouseholdNotFound()),
            HouseholdNotFoundError.class),
        errorCase(
            "Profile destination unchanged",
            () -> LifecycleErrors.toTransferProfileError(new TransferRejections.SameHousehold()),
            SameHouseholdError.class),
        errorCase(
            "Linked Profile",
            () -> LifecycleErrors.toTransferProfileError(new TransferRejections.ProfileLinked()),
            ProfileBelongsToAccountError.class),
        errorCase(
            "Local manager required",
            () ->
                LifecycleErrors.toTransferProfileError(
                    new TransferRejections.LocalManagerRequired()),
            EligibleProfileManagerRequiredError.class),
        errorCase(
            "Local manager not found",
            () ->
                LifecycleErrors.toTransferProfileError(
                    new TransferRejections.LocalManagerNotFound()),
            AccountNotFoundError.class),
        errorCase(
            "Local manager ineligible",
            () ->
                LifecycleErrors.toTransferProfileError(
                    new TransferRejections.ReplacementManagerNotEligible()),
            ProfileManagerNotEligibleError.class),
        errorCase(
            "Profile name conflict",
            () -> LifecycleErrors.toTransferProfileError(new TransferRejections.NameConflict()),
            ProfileNameTakenError.class),
        errorCase(
            "Profile leaves no eligible HouseholdAdmin",
            () -> LifecycleErrors.toTransferProfileError(new TransferRejections.NoEligibleAdmin()),
            RestrictedProfileRequiresHouseholdAdminError.class));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("deletionErrorCases")
  @DisplayName("Should map to the expected schema error when an Account deletion is rejected")
  void shouldMapToExpectedSchemaErrorWhenAccountDeletionIsRejected(
      String caseName, Supplier<Object> mapping, Class<?> expectedType) {
    assertThat(mapping.get()).isInstanceOf(expectedType);
  }

  private static Stream<Arguments> deletionErrorCases() {
    return Stream.of(
        errorCase(
            "Account not found",
            () -> LifecycleErrors.toDeleteAccountError(new TransferRejections.AccountNotFound()),
            AccountNotFoundError.class),
        errorCase(
            "Account deletion reason required",
            () -> LifecycleErrors.toDeleteAccountError(new TransferRejections.ReasonRequired()),
            ReasonRequiredError.class),
        errorCase(
            "Account deletion reauthentication required",
            () ->
                LifecycleErrors.toDeleteAccountError(
                    new TransferRejections.ReauthenticationRequired()),
            ReauthenticationRequiredError.class),
        errorCase(
            "Final Account",
            () -> LifecycleErrors.toDeleteAccountError(new TransferRejections.FinalAccount()),
            LastHouseholdAccountError.class),
        errorCase(
            "Last HouseholdAdmin",
            () -> LifecycleErrors.toDeleteAccountError(new TransferRejections.LastHouseholdAdmin()),
            LastHouseholdAdminError.class),
        errorCase(
            "Last ServerAdmin",
            () -> LifecycleErrors.toDeleteAccountError(new TransferRejections.LastServerAdmin()),
            LastServerAdminError.class),
        errorCase(
            "Replacement manager required",
            () ->
                LifecycleErrors.toDeleteAccountError(
                    new TransferRejections.ReplacementManagerRequired()),
            ReplacementManagerRequiredError.class),
        errorCase(
            "Replacement manager not found",
            () ->
                LifecycleErrors.toDeleteAccountError(
                    new TransferRejections.ReplacementManagerNotFound()),
            AccountNotFoundError.class),
        errorCase(
            "Replacement manager ineligible",
            () ->
                LifecycleErrors.toDeleteAccountError(
                    new TransferRejections.ReplacementManagerNotEligible()),
            ProfileManagerNotEligibleError.class),
        errorCase(
            "Account Profile needs an eligible Profile manager",
            () ->
                LifecycleErrors.toDeleteAccountError(
                    new TransferRejections.EligibleManagerRequired()),
            ProfileRequiresEligibleManagerError.class),
        errorCase(
            "Account leaves no eligible HouseholdAdmin",
            () -> LifecycleErrors.toDeleteAccountError(new TransferRejections.NoEligibleAdmin()),
            RestrictedProfileRequiresHouseholdAdminError.class),
        errorCase(
            "Self-deletion confirmation required",
            () ->
                LifecycleErrors.toDeleteMyAccountError(
                    new TransferRejections.ConfirmationRequired()),
            ConfirmationRequiredError.class),
        errorCase(
            "Self-deletion reauthentication required",
            () ->
                LifecycleErrors.toDeleteMyAccountError(
                    new TransferRejections.ReauthenticationRequired()),
            ReauthenticationRequiredError.class),
        errorCase(
            "Self-deletion of final Account",
            () -> LifecycleErrors.toDeleteMyAccountError(new TransferRejections.FinalAccount()),
            LastHouseholdAccountError.class),
        errorCase(
            "Self-deletion of last HouseholdAdmin",
            () ->
                LifecycleErrors.toDeleteMyAccountError(new TransferRejections.LastHouseholdAdmin()),
            LastHouseholdAdminError.class),
        errorCase(
            "Self-deletion of last ServerAdmin",
            () -> LifecycleErrors.toDeleteMyAccountError(new TransferRejections.LastServerAdmin()),
            LastServerAdminError.class),
        errorCase(
            "Self-deletion leaves Profile without an eligible Profile manager",
            () ->
                LifecycleErrors.toDeleteMyAccountError(
                    new TransferRejections.EligibleManagerRequired()),
            ProfileRequiresEligibleManagerError.class),
        errorCase(
            "Self-deletion leaves no eligible HouseholdAdmin",
            () -> LifecycleErrors.toDeleteMyAccountError(new TransferRejections.NoEligibleAdmin()),
            RestrictedProfileRequiresHouseholdAdminError.class),
        errorCase(
            "Administrative Profile deletion not found",
            () ->
                LifecycleErrors.toAdministrativelyDeleteProfileError(
                    new TransferRejections.ProfileNotFound()),
            ProfileNotFoundError.class),
        errorCase(
            "Administrative Profile deletion reason required",
            () ->
                LifecycleErrors.toAdministrativelyDeleteProfileError(
                    new TransferRejections.ReasonRequired()),
            ReasonRequiredError.class),
        errorCase(
            "Administrative Profile deletion reauthentication required",
            () ->
                LifecycleErrors.toAdministrativelyDeleteProfileError(
                    new TransferRejections.ReauthenticationRequired()),
            ReauthenticationRequiredError.class),
        errorCase(
            "Administrative deletion of a linked Profile",
            () ->
                LifecycleErrors.toAdministrativelyDeleteProfileError(
                    new TransferRejections.ProfileLinked()),
            ProfileBelongsToAccountError.class));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("replacementEligibilityCases")
  @DisplayName("Should map to the mutation input field when a replacement is ineligible")
  void shouldMapToMutationInputFieldWhenReplacementIsIneligible(
      String caseName, Supplier<List<String>> inputPath, String expectedField) {
    assertThat(inputPath.get()).containsExactly(expectedField);
  }

  private static Stream<Arguments> replacementEligibilityCases() {
    var accountDeletion =
        (ProfileManagerNotEligibleError)
            LifecycleErrors.toDeleteAccountError(
                new TransferRejections.ReplacementManagerNotEligible());
    var profileTransfer =
        (ProfileManagerNotEligibleError)
            LifecycleErrors.toTransferProfileError(
                new TransferRejections.ReplacementManagerNotEligible());

    return Stream.of(
        Arguments.of(
            "Account deletion",
            (Supplier<List<String>>) accountDeletion::inputPath,
            "replacementManagerAccountId"),
        Arguments.of(
            "Profile transfer",
            (Supplier<List<String>>) profileTransfer::inputPath,
            "profileManagerAccountId"));
  }

  private static Arguments errorCase(
      String label, Supplier<Object> mapping, Class<?> expectedType) {
    return Arguments.of(label, mapping, expectedType);
  }
}
