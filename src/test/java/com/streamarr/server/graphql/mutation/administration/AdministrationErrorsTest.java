package com.streamarr.server.graphql.mutation.administration;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.graphql.mutation.MutationError;
import com.streamarr.server.services.identity.AdministrationRejections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Administration Error Mapping Tests")
class AdministrationErrorsTest {

  private static final AccountNotFoundError ACCOUNT_NOT_FOUND =
      new AccountNotFoundError("No such Account.", List.of("accountId"));
  private static final ReauthenticationRequiredError REAUTHENTICATION_REQUIRED =
      new ReauthenticationRequiredError("Confirm your password before retrying this action.");
  private static final ReasonRequiredError REASON_REQUIRED =
      new ReasonRequiredError("Enter a reason for the audit record.", List.of("reason"));
  private static final RestrictedAccountCannotAdministerError RESTRICTED_ACCOUNT =
      new RestrictedAccountCannotAdministerError(
          "An Account with a restricted Personal Profile cannot be a ServerAdmin, HouseholdAdmin, or Profile manager.",
          List.of("accountId"));
  private static final LastServerAdminError LAST_SERVER_ADMIN =
      new LastServerAdminError("At least one enabled ServerAdmin must remain.");
  private static final LastHouseholdAdminError LAST_HOUSEHOLD_ADMIN =
      new LastHouseholdAdminError("A Household keeps at least one HouseholdAdmin.");
  private static final DisplayNameRequiredError DISPLAY_NAME_REQUIRED =
      new DisplayNameRequiredError("Enter a display name.", List.of("displayName"));
  private static final HouseholdNameRequiredError HOUSEHOLD_NAME_REQUIRED =
      new HouseholdNameRequiredError("Enter a household name.", List.of("name"));
  private static final HouseholdNotFoundError HOUSEHOLD_NOT_FOUND =
      new HouseholdNotFoundError("No such Household.", List.of("householdId"));

  @ParameterizedTest(name = "Should map {0}")
  @MethodSource("rejectionMappings")
  @DisplayName("Should map every administration rejection when building GraphQL errors")
  void shouldMapEveryAdministrationRejectionWhenBuildingGraphqlErrors(
      String description, Supplier<? extends MutationError> mapping, MutationError expected) {
    assertThat(mapping.get()).as(description).isEqualTo(expected);
  }

  static Stream<Arguments> rejectionMappings() {
    return Stream.of(
        mapping(
            "grant server admin / Account not found",
            () ->
                AdministrationErrors.toGrantServerAdminError(
                    new AdministrationRejections.AccountNotFound()),
            ACCOUNT_NOT_FOUND),
        mapping(
            "grant server admin / reauthentication required",
            () ->
                AdministrationErrors.toGrantServerAdminError(
                    new AdministrationRejections.ReauthenticationRequired()),
            REAUTHENTICATION_REQUIRED),
        mapping(
            "grant server admin / reason required",
            () ->
                AdministrationErrors.toGrantServerAdminError(
                    new AdministrationRejections.ReasonRequired()),
            REASON_REQUIRED),
        mapping(
            "grant server admin / restricted Account",
            () ->
                AdministrationErrors.toGrantServerAdminError(
                    new AdministrationRejections.RestrictedAccount()),
            RESTRICTED_ACCOUNT),
        mapping(
            "revoke server admin / Account not found",
            () ->
                AdministrationErrors.toRevokeServerAdminError(
                    new AdministrationRejections.AccountNotFound()),
            ACCOUNT_NOT_FOUND),
        mapping(
            "revoke server admin / reauthentication required",
            () ->
                AdministrationErrors.toRevokeServerAdminError(
                    new AdministrationRejections.ReauthenticationRequired()),
            REAUTHENTICATION_REQUIRED),
        mapping(
            "revoke server admin / reason required",
            () ->
                AdministrationErrors.toRevokeServerAdminError(
                    new AdministrationRejections.ReasonRequired()),
            REASON_REQUIRED),
        mapping(
            "revoke server admin / last ServerAdmin",
            () ->
                AdministrationErrors.toRevokeServerAdminError(
                    new AdministrationRejections.LastServerAdmin()),
            LAST_SERVER_ADMIN),
        mapping(
            "grant HouseholdAdmin / Account not found",
            () ->
                AdministrationErrors.toGrantHouseholdAdminError(
                    new AdministrationRejections.AccountNotFound()),
            ACCOUNT_NOT_FOUND),
        mapping(
            "grant HouseholdAdmin / restricted Account",
            () ->
                AdministrationErrors.toGrantHouseholdAdminError(
                    new AdministrationRejections.RestrictedAccount()),
            RESTRICTED_ACCOUNT),
        mapping(
            "revoke HouseholdAdmin / Account not found",
            () ->
                AdministrationErrors.toRevokeHouseholdAdminError(
                    new AdministrationRejections.AccountNotFound()),
            ACCOUNT_NOT_FOUND),
        mapping(
            "revoke HouseholdAdmin / last HouseholdAdmin",
            () ->
                AdministrationErrors.toRevokeHouseholdAdminError(
                    new AdministrationRejections.LastHouseholdAdmin()),
            LAST_HOUSEHOLD_ADMIN),
        mapping(
            "disable Account / Account not found",
            () ->
                AdministrationErrors.toDisableAccountError(
                    new AdministrationRejections.AccountNotFound()),
            ACCOUNT_NOT_FOUND),
        mapping(
            "disable Account / last ServerAdmin",
            () ->
                AdministrationErrors.toDisableAccountError(
                    new AdministrationRejections.LastServerAdmin()),
            LAST_SERVER_ADMIN),
        mapping(
            "enable Account / Account not found",
            () ->
                AdministrationErrors.toEnableAccountError(
                    new AdministrationRejections.AccountNotFound()),
            ACCOUNT_NOT_FOUND),
        mapping(
            "rename Account / Account not found",
            () ->
                AdministrationErrors.toRenameAccountError(
                    new AdministrationRejections.AccountNotFound()),
            ACCOUNT_NOT_FOUND),
        mapping(
            "rename Account / display name required",
            () ->
                AdministrationErrors.toRenameAccountError(
                    new AdministrationRejections.DisplayNameRequired()),
            DISPLAY_NAME_REQUIRED),
        mapping(
            "create Household / name required",
            () ->
                AdministrationErrors.toCreateHouseholdError(
                    new AdministrationRejections.HouseholdNameRequired()),
            HOUSEHOLD_NAME_REQUIRED),
        mapping(
            "rename Household / Household not found",
            () ->
                AdministrationErrors.toRenameHouseholdError(
                    new AdministrationRejections.HouseholdNotFound()),
            HOUSEHOLD_NOT_FOUND),
        mapping(
            "rename Household / name required",
            () ->
                AdministrationErrors.toRenameHouseholdError(
                    new AdministrationRejections.HouseholdNameRequired()),
            HOUSEHOLD_NAME_REQUIRED));
  }

  private static Arguments mapping(
      String description, Supplier<? extends MutationError> mapping, MutationError expected) {
    return Arguments.of(description, mapping, expected);
  }
}
