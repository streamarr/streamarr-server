package com.streamarr.server.graphql.mutation.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.identity.TransferRejections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every service rejection maps to exactly its schema error type. */
@Tag("UnitTest")
@DisplayName("Lifecycle Errors Tests")
class LifecycleErrorsTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("transferErrorCases")
  @DisplayName("Should map each transfer rejection to its schema error")
  void shouldMapEachTransferRejectionToItsSchemaError(
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
            FinalAccountError.class),
        errorCase(
            "Last HouseholdAdmin",
            () ->
                LifecycleErrors.toTransferAccountError(new TransferRejections.LastHouseholdAdmin()),
            LastHouseholdAdminError.class),
        errorCase(
            "No eligible HouseholdAdmin",
            () -> LifecycleErrors.toTransferAccountError(new TransferRejections.NoEligibleAdmin()),
            NoEligibleAdminError.class),
        errorCase(
            "Account Profile name conflict",
            () -> LifecycleErrors.toTransferAccountError(new TransferRejections.NameConflict()),
            ProfileNameTakenError.class),
        errorCase(
            "Account Profile anchor required",
            () -> LifecycleErrors.toTransferAccountError(new TransferRejections.AnchorRequired()),
            HomeAnchorRequiredError.class),
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
            ProfileLinkedError.class),
        errorCase(
            "Local manager required",
            () ->
                LifecycleErrors.toTransferProfileError(
                    new TransferRejections.LocalManagerRequired()),
            LocalManagerRequiredError.class),
        errorCase(
            "Local manager not found",
            () ->
                LifecycleErrors.toTransferProfileError(
                    new TransferRejections.LocalManagerNotFound()),
            LocalManagerNotFoundError.class),
        errorCase(
            "Local manager ineligible",
            () ->
                LifecycleErrors.toTransferProfileError(
                    new TransferRejections.ReplacementManagerNotEligible()),
            ReplacementManagerNotEligibleError.class),
        errorCase(
            "Profile name conflict",
            () -> LifecycleErrors.toTransferProfileError(new TransferRejections.NameConflict()),
            ProfileNameTakenError.class),
        errorCase(
            "Profile leaves no eligible HouseholdAdmin",
            () -> LifecycleErrors.toTransferProfileError(new TransferRejections.NoEligibleAdmin()),
            NoEligibleAdminError.class));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("deletionErrorCases")
  @DisplayName("Should map each deletion rejection to its schema error")
  void shouldMapEachDeletionRejectionToItsSchemaError(
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
            FinalAccountError.class),
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
            ReplacementManagerNotFoundError.class),
        errorCase(
            "Replacement manager ineligible",
            () ->
                LifecycleErrors.toDeleteAccountError(
                    new TransferRejections.ReplacementManagerNotEligible()),
            ReplacementManagerNotEligibleError.class),
        errorCase(
            "Account Profile anchor required",
            () -> LifecycleErrors.toDeleteAccountError(new TransferRejections.AnchorRequired()),
            HomeAnchorRequiredError.class),
        errorCase(
            "Account leaves no eligible HouseholdAdmin",
            () -> LifecycleErrors.toDeleteAccountError(new TransferRejections.NoEligibleAdmin()),
            NoEligibleAdminError.class),
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
            FinalAccountError.class),
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
            "Self-deletion leaves Profile without anchor",
            () -> LifecycleErrors.toDeleteMyAccountError(new TransferRejections.AnchorRequired()),
            HomeAnchorRequiredError.class),
        errorCase(
            "Self-deletion leaves no eligible HouseholdAdmin",
            () -> LifecycleErrors.toDeleteMyAccountError(new TransferRejections.NoEligibleAdmin()),
            NoEligibleAdminError.class),
        errorCase(
            "Force-delete Profile not found",
            () ->
                LifecycleErrors.toForceDeleteProfileError(new TransferRejections.ProfileNotFound()),
            ProfileNotFoundError.class),
        errorCase(
            "Force-delete reason required",
            () ->
                LifecycleErrors.toForceDeleteProfileError(new TransferRejections.ReasonRequired()),
            ReasonRequiredError.class),
        errorCase(
            "Force-delete reauthentication required",
            () ->
                LifecycleErrors.toForceDeleteProfileError(
                    new TransferRejections.ReauthenticationRequired()),
            ReauthenticationRequiredError.class),
        errorCase(
            "Force-delete linked Profile",
            () -> LifecycleErrors.toForceDeleteProfileError(new TransferRejections.ProfileLinked()),
            ProfileLinkedError.class));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("replacementEligibilityCases")
  @DisplayName("Should map replacement eligibility to the mutation input field")
  void shouldMapReplacementEligibilityToMutationInputField(
      String caseName, Supplier<List<String>> inputPath, String expectedField) {
    assertThat(inputPath.get()).containsExactly(expectedField);
  }

  private static Stream<Arguments> replacementEligibilityCases() {
    return Stream.of(
        Arguments.of(
            "Account deletion",
            (Supplier<List<String>>)
                () ->
                    ((ReplacementManagerNotEligibleError)
                            LifecycleErrors.toDeleteAccountError(
                                new TransferRejections.ReplacementManagerNotEligible()))
                        .inputPath(),
            "replacementManagerAccountId"),
        Arguments.of(
            "Profile transfer",
            (Supplier<List<String>>)
                () ->
                    ((ReplacementManagerNotEligibleError)
                            LifecycleErrors.toTransferProfileError(
                                new TransferRejections.ReplacementManagerNotEligible()))
                        .inputPath(),
            "localManagerAccountId"));
  }

  private static Arguments errorCase(
      String label, Supplier<Object> mapping, Class<?> expectedType) {
    return Arguments.of(label, mapping, expectedType);
  }
}
