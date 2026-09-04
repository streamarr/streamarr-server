package com.streamarr.server.graphql.mutation.identity.lifecycle;

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
@DisplayName("Identity Lifecycle Errors Tests")
class IdentityLifecycleErrorsTest {

  @Test
  @DisplayName(
      "Should omit implementation detail when the eligible Profile manager requirement is described")
  void shouldOmitImplementationDetailWhenEligibleProfileManagerRequirementIsDescribed() {
    var error =
        IdentityLifecycleErrors.toTransferAccountError(
            new TransferRejections.EligibleManagerRequired());

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
    var error =
        IdentityLifecycleErrors.toTransferProfileError(new TransferRejections.ProfileLinked());

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
    var error =
        IdentityLifecycleErrors.toAdministrativelyDeleteAccountError(
            new TransferRejections.LastServerAdmin());

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
    var error =
        IdentityLifecycleErrors.toTransferAccountError(new TransferRejections.LastHouseholdAdmin());

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
    var error =
        IdentityLifecycleErrors.toAdministrativelyDeleteAccountError(
            new TransferRejections.FinalAccount());

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
        IdentityLifecycleErrors.toTransferProfileError(
            new TransferRejections.LocalManagerRequired());

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
        IdentityLifecycleErrors.toTransferAccountError(
            new TransferRejections.RestrictedFirstAccount());

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
        IdentityLifecycleErrors.toAdministrativelyDeleteAccountError(
            new TransferRejections.ReplacementManagerRequired());

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
            () ->
                IdentityLifecycleErrors.toTransferAccountError(
                    new TransferRejections.AccountNotFound()),
            AccountNotFoundError.class),
        errorCase(
            "Account destination not found",
            () ->
                IdentityLifecycleErrors.toTransferAccountError(
                    new TransferRejections.HouseholdNotFound()),
            HouseholdNotFoundError.class),
        errorCase(
            "Account destination unchanged",
            () ->
                IdentityLifecycleErrors.toTransferAccountError(
                    new TransferRejections.SameHousehold()),
            SameHouseholdError.class),
        errorCase(
            "Final Account",
            () ->
                IdentityLifecycleErrors.toTransferAccountError(
                    new TransferRejections.FinalAccount()),
            LastHouseholdAccountError.class),
        errorCase(
            "Last HouseholdAdmin",
            () ->
                IdentityLifecycleErrors.toTransferAccountError(
                    new TransferRejections.LastHouseholdAdmin()),
            LastHouseholdAdminError.class),
        errorCase(
            "No eligible HouseholdAdmin",
            () ->
                IdentityLifecycleErrors.toTransferAccountError(
                    new TransferRejections.NoEligibleAdmin()),
            RestrictedProfileRequiresHouseholdAdminError.class),
        errorCase(
            "Account Profile name conflict",
            () ->
                IdentityLifecycleErrors.toTransferAccountError(
                    new TransferRejections.NameConflict()),
            ProfileNameTakenError.class),
        errorCase(
            "Account Profile needs an eligible Profile manager",
            () ->
                IdentityLifecycleErrors.toTransferAccountError(
                    new TransferRejections.EligibleManagerRequired()),
            ProfileRequiresEligibleManagerError.class),
        errorCase(
            "Restricted first Account",
            () ->
                IdentityLifecycleErrors.toTransferAccountError(
                    new TransferRejections.RestrictedFirstAccount()),
            RestrictedFirstAccountError.class),
        errorCase(
            "Profile not found",
            () ->
                IdentityLifecycleErrors.toTransferProfileError(
                    new TransferRejections.ProfileNotFound()),
            ProfileNotFoundError.class),
        errorCase(
            "Profile destination not found",
            () ->
                IdentityLifecycleErrors.toTransferProfileError(
                    new TransferRejections.HouseholdNotFound()),
            HouseholdNotFoundError.class),
        errorCase(
            "Profile destination unchanged",
            () ->
                IdentityLifecycleErrors.toTransferProfileError(
                    new TransferRejections.SameHousehold()),
            SameHouseholdError.class),
        errorCase(
            "Linked Profile",
            () ->
                IdentityLifecycleErrors.toTransferProfileError(
                    new TransferRejections.ProfileLinked()),
            ProfileBelongsToAccountError.class),
        errorCase(
            "Local manager required",
            () ->
                IdentityLifecycleErrors.toTransferProfileError(
                    new TransferRejections.LocalManagerRequired()),
            EligibleProfileManagerRequiredError.class),
        errorCase(
            "Local manager not found",
            () ->
                IdentityLifecycleErrors.toTransferProfileError(
                    new TransferRejections.LocalManagerNotFound()),
            AccountNotFoundError.class),
        errorCase(
            "Local manager ineligible",
            () ->
                IdentityLifecycleErrors.toTransferProfileError(
                    new TransferRejections.ReplacementManagerNotEligible()),
            ProfileManagerNotEligibleError.class),
        errorCase(
            "Profile name conflict",
            () ->
                IdentityLifecycleErrors.toTransferProfileError(
                    new TransferRejections.NameConflict()),
            ProfileNameTakenError.class),
        errorCase(
            "Profile leaves no eligible HouseholdAdmin",
            () ->
                IdentityLifecycleErrors.toTransferProfileError(
                    new TransferRejections.NoEligibleAdmin()),
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
            () ->
                IdentityLifecycleErrors.toAdministrativelyDeleteAccountError(
                    new TransferRejections.AccountNotFound()),
            AccountNotFoundError.class),
        errorCase(
            "Account deletion reason required",
            () ->
                IdentityLifecycleErrors.toAdministrativelyDeleteAccountError(
                    new TransferRejections.ReasonRequired()),
            ReasonRequiredError.class),
        errorCase(
            "Account deletion reauthentication required",
            () ->
                IdentityLifecycleErrors.toAdministrativelyDeleteAccountError(
                    new TransferRejections.ReauthenticationRequired()),
            ReauthenticationRequiredError.class),
        errorCase(
            "Final Account",
            () ->
                IdentityLifecycleErrors.toAdministrativelyDeleteAccountError(
                    new TransferRejections.FinalAccount()),
            LastHouseholdAccountError.class),
        errorCase(
            "Last HouseholdAdmin",
            () ->
                IdentityLifecycleErrors.toAdministrativelyDeleteAccountError(
                    new TransferRejections.LastHouseholdAdmin()),
            LastHouseholdAdminError.class),
        errorCase(
            "Last ServerAdmin",
            () ->
                IdentityLifecycleErrors.toAdministrativelyDeleteAccountError(
                    new TransferRejections.LastServerAdmin()),
            LastServerAdminError.class),
        errorCase(
            "Replacement manager required",
            () ->
                IdentityLifecycleErrors.toAdministrativelyDeleteAccountError(
                    new TransferRejections.ReplacementManagerRequired()),
            ReplacementManagerRequiredError.class),
        errorCase(
            "Replacement manager not found",
            () ->
                IdentityLifecycleErrors.toAdministrativelyDeleteAccountError(
                    new TransferRejections.ReplacementManagerNotFound()),
            AccountNotFoundError.class),
        errorCase(
            "Replacement manager ineligible",
            () ->
                IdentityLifecycleErrors.toAdministrativelyDeleteAccountError(
                    new TransferRejections.ReplacementManagerNotEligible()),
            ProfileManagerNotEligibleError.class),
        errorCase(
            "Account Profile needs an eligible Profile manager",
            () ->
                IdentityLifecycleErrors.toAdministrativelyDeleteAccountError(
                    new TransferRejections.EligibleManagerRequired()),
            ProfileRequiresEligibleManagerError.class),
        errorCase(
            "Account leaves no eligible HouseholdAdmin",
            () ->
                IdentityLifecycleErrors.toAdministrativelyDeleteAccountError(
                    new TransferRejections.NoEligibleAdmin()),
            RestrictedProfileRequiresHouseholdAdminError.class),
        errorCase(
            "Self-deletion confirmation required",
            () ->
                IdentityLifecycleErrors.toDeleteMyAccountError(
                    new TransferRejections.ConfirmationRequired()),
            ConfirmationRequiredError.class),
        errorCase(
            "Self-deletion reauthentication required",
            () ->
                IdentityLifecycleErrors.toDeleteMyAccountError(
                    new TransferRejections.ReauthenticationRequired()),
            ReauthenticationRequiredError.class),
        errorCase(
            "Self-deletion of final Account",
            () ->
                IdentityLifecycleErrors.toDeleteMyAccountError(
                    new TransferRejections.FinalAccount()),
            LastHouseholdAccountError.class),
        errorCase(
            "Self-deletion of last HouseholdAdmin",
            () ->
                IdentityLifecycleErrors.toDeleteMyAccountError(
                    new TransferRejections.LastHouseholdAdmin()),
            LastHouseholdAdminError.class),
        errorCase(
            "Self-deletion of last ServerAdmin",
            () ->
                IdentityLifecycleErrors.toDeleteMyAccountError(
                    new TransferRejections.LastServerAdmin()),
            LastServerAdminError.class),
        errorCase(
            "Self-deletion leaves Profile without an eligible Profile manager",
            () ->
                IdentityLifecycleErrors.toDeleteMyAccountError(
                    new TransferRejections.EligibleManagerRequired()),
            ProfileRequiresEligibleManagerError.class),
        errorCase(
            "Self-deletion leaves no eligible HouseholdAdmin",
            () ->
                IdentityLifecycleErrors.toDeleteMyAccountError(
                    new TransferRejections.NoEligibleAdmin()),
            RestrictedProfileRequiresHouseholdAdminError.class),
        errorCase(
            "Administrative Profile deletion not found",
            () ->
                IdentityLifecycleErrors.toAdministrativelyDeleteProfileError(
                    new TransferRejections.ProfileNotFound()),
            ProfileNotFoundError.class),
        errorCase(
            "Administrative Profile deletion reason required",
            () ->
                IdentityLifecycleErrors.toAdministrativelyDeleteProfileError(
                    new TransferRejections.ReasonRequired()),
            ReasonRequiredError.class),
        errorCase(
            "Administrative Profile deletion reauthentication required",
            () ->
                IdentityLifecycleErrors.toAdministrativelyDeleteProfileError(
                    new TransferRejections.ReauthenticationRequired()),
            ReauthenticationRequiredError.class),
        errorCase(
            "Administrative deletion of a linked Profile",
            () ->
                IdentityLifecycleErrors.toAdministrativelyDeleteProfileError(
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
            IdentityLifecycleErrors.toAdministrativelyDeleteAccountError(
                new TransferRejections.ReplacementManagerNotEligible());
    var profileTransfer =
        (ProfileManagerNotEligibleError)
            IdentityLifecycleErrors.toTransferProfileError(
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
