package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.identity.AdministrationRejections;

/** The exhaustive mappings from service rejection to schema error type, one per union. */
public final class AdministrationErrors {

  private static final String ACCOUNT_ID = "accountId";
  private static final String REASON = "reason";
  private static final String NAME = "name";

  private AdministrationErrors() {}

  public static GrantServerAdminError toGrantServerAdminError(
      AdministrationRejections.GrantServerAdmin rejection) {
    return switch (rejection) {
      case AdministrationRejections.AccountNotFound _ -> accountNotFound();
      case AdministrationRejections.ReauthenticationRequired _ -> reauthenticationRequired();
      case AdministrationRejections.ReasonRequired _ -> reasonRequired();
      case AdministrationRejections.RestrictedAccount _ -> restrictedAccount();
    };
  }

  public static RevokeServerAdminError toRevokeServerAdminError(
      AdministrationRejections.RevokeServerAdmin rejection) {
    return switch (rejection) {
      case AdministrationRejections.AccountNotFound _ -> accountNotFound();
      case AdministrationRejections.ReauthenticationRequired _ -> reauthenticationRequired();
      case AdministrationRejections.ReasonRequired _ -> reasonRequired();
      case AdministrationRejections.LastServerAdmin _ -> lastServerAdmin();
    };
  }

  public static GrantHouseholdAdminError toGrantHouseholdAdminError(
      AdministrationRejections.GrantHouseholdAdmin rejection) {
    return switch (rejection) {
      case AdministrationRejections.AccountNotFound _ -> accountNotFound();
      case AdministrationRejections.RestrictedAccount _ -> restrictedAccount();
    };
  }

  public static RevokeHouseholdAdminError toRevokeHouseholdAdminError(
      AdministrationRejections.RevokeHouseholdAdmin rejection) {
    return switch (rejection) {
      case AdministrationRejections.AccountNotFound _ -> accountNotFound();
      case AdministrationRejections.LastHouseholdAdmin _ ->
          new LastHouseholdAdminError("A Household keeps at least one HouseholdAdmin.");
    };
  }

  public static DisableAccountError toDisableAccountError(
      AdministrationRejections.DisableAccount rejection) {
    return switch (rejection) {
      case AdministrationRejections.AccountNotFound _ -> accountNotFound();
      case AdministrationRejections.LastServerAdmin _ -> lastServerAdmin();
    };
  }

  public static EnableAccountError toEnableAccountError(
      AdministrationRejections.EnableAccount rejection) {
    return switch (rejection) {
      case AdministrationRejections.AccountNotFound _ -> accountNotFound();
    };
  }

  public static RenameAccountError toRenameAccountError(
      AdministrationRejections.RenameAccount rejection) {
    return switch (rejection) {
      case AdministrationRejections.AccountNotFound _ -> accountNotFound();
      case AdministrationRejections.DisplayNameRequired _ ->
          new DisplayNameRequiredError("Enter a display name.", InputPath.of("displayName"));
    };
  }

  public static CreateHouseholdError toCreateHouseholdError(
      AdministrationRejections.CreateHousehold rejection) {
    return switch (rejection) {
      case AdministrationRejections.HouseholdNameRequired _ -> householdNameRequired();
    };
  }

  public static RenameHouseholdError toRenameHouseholdError(
      AdministrationRejections.RenameHousehold rejection) {
    return switch (rejection) {
      case AdministrationRejections.HouseholdNotFound _ ->
          new HouseholdNotFoundError("No such Household.", InputPath.of("householdId"));
      case AdministrationRejections.HouseholdNameRequired _ -> householdNameRequired();
    };
  }

  public static InvalidIdError invalidAccountId() {
    return invalidId(ACCOUNT_ID);
  }

  public static InvalidIdError invalidHouseholdId() {
    return invalidId("householdId");
  }

  private static AccountNotFoundError accountNotFound() {
    return new AccountNotFoundError("No such Account.", InputPath.of(ACCOUNT_ID));
  }

  private static ReauthenticationRequiredError reauthenticationRequired() {
    return new ReauthenticationRequiredError("Confirm your password to continue.");
  }

  private static ReasonRequiredError reasonRequired() {
    return new ReasonRequiredError("Enter a reason for the audit record.", InputPath.of(REASON));
  }

  private static RestrictedAccountAuthorityError restrictedAccount() {
    return new RestrictedAccountAuthorityError(
        "An Account with a restricted Personal Profile cannot hold authority.",
        InputPath.of(ACCOUNT_ID));
  }

  private static LastServerAdminError lastServerAdmin() {
    return new LastServerAdminError("At least one enabled ServerAdmin must remain.");
  }

  private static HouseholdNameRequiredError householdNameRequired() {
    return new HouseholdNameRequiredError("Enter a household name.", InputPath.of(NAME));
  }

  private static InvalidIdError invalidId(String inputName) {
    return new InvalidIdError("Enter a valid ID.", InputPath.of(inputName));
  }
}
