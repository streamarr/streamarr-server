package com.streamarr.server.graphql.mutation.lifecycle;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.identity.TransferRejections;

/** The exhaustive mappings from service rejection to schema error type, one per union. */
public final class LifecycleErrors {

  private static final String ACCOUNT_ID = "accountId";
  private static final String PROFILE_ID = "profileId";
  private static final String DESTINATION = "destinationHouseholdId";
  private static final String LOCAL_MANAGER = "localManagerAccountId";
  private static final String NO_SUCH_ACCOUNT = "No such Account.";

  private LifecycleErrors() {}

  public static TransferAccountError toTransferAccountError(
      TransferRejections.TransferAccount rejection) {
    return switch (rejection) {
      case TransferRejections.AccountNotFound _ -> accountNotFound();
      case TransferRejections.HouseholdNotFound _ -> householdNotFound();
      case TransferRejections.SameHousehold _ -> sameHousehold();
      case TransferRejections.FinalAccount _ -> finalAccount();
      case TransferRejections.LastHouseholdAdmin _ -> lastHouseholdAdmin();
      case TransferRejections.NoEligibleAdmin _ -> noEligibleAdmin();
      case TransferRejections.NameConflict _ -> nameTaken();
      case TransferRejections.EligibleManagerRequired _ -> eligibleManagerRequired();
      case TransferRejections.RestrictedFirstAccount _ ->
          new RestrictedFirstAccountError(
              "The first Account of an empty Household becomes HouseholdAdmin; it cannot be"
                  + " restricted.");
    };
  }

  public static DeleteAccountError toDeleteAccountError(
      TransferRejections.DeleteAccount rejection) {
    return switch (rejection) {
      case TransferRejections.AccountNotFound _ -> accountNotFound();
      case TransferRejections.ReasonRequired _ -> reasonRequired();
      case TransferRejections.ReauthenticationRequired _ -> reauthenticationRequired();
      case TransferRejections.FinalAccount _ -> finalAccount();
      case TransferRejections.LastHouseholdAdmin _ -> lastHouseholdAdmin();
      case TransferRejections.LastServerAdmin _ -> lastServerAdmin();
      case TransferRejections.ReplacementManagerRequired _ ->
          new ReplacementManagerRequiredError(
              "KEEP preserves the Profile only with a replacement manager named up front.",
              InputPath.of("replacementManagerAccountId"));
      case TransferRejections.ReplacementManagerNotFound _ ->
          new ReplacementManagerNotFoundError(
              NO_SUCH_ACCOUNT, InputPath.of("replacementManagerAccountId"));
      case TransferRejections.ReplacementManagerNotEligible _ ->
          replacementNotEligible("replacementManagerAccountId");
      case TransferRejections.EligibleManagerRequired _ -> eligibleManagerRequired();
      case TransferRejections.NoEligibleAdmin _ -> noEligibleAdmin();
    };
  }

  public static DeleteMyAccountError toDeleteMyAccountError(
      TransferRejections.DeleteMyAccount rejection) {
    return switch (rejection) {
      case TransferRejections.ConfirmationRequired _ ->
          new ConfirmationRequiredError(
              "Type DELETE to confirm deleting your Account.", InputPath.of("confirmation"));
      case TransferRejections.ReauthenticationRequired _ -> reauthenticationRequired();
      case TransferRejections.FinalAccount _ -> finalAccount();
      case TransferRejections.LastHouseholdAdmin _ -> lastHouseholdAdmin();
      case TransferRejections.LastServerAdmin _ -> lastServerAdmin();
      case TransferRejections.EligibleManagerRequired _ -> eligibleManagerRequired();
      case TransferRejections.NoEligibleAdmin _ -> noEligibleAdmin();
    };
  }

  public static TransferProfileError toTransferProfileError(
      TransferRejections.TransferProfile rejection) {
    return switch (rejection) {
      case TransferRejections.ProfileNotFound _ -> profileNotFound();
      case TransferRejections.HouseholdNotFound _ -> householdNotFound();
      case TransferRejections.SameHousehold _ -> sameHousehold();
      case TransferRejections.ProfileLinked _ -> profileLinked();
      case TransferRejections.LocalManagerRequired _ ->
          new LocalManagerRequiredError(
              "Name an eligible manager in the destination Household.",
              InputPath.of(LOCAL_MANAGER));
      case TransferRejections.LocalManagerNotFound _ ->
          new LocalManagerNotFoundError(NO_SUCH_ACCOUNT, InputPath.of(LOCAL_MANAGER));
      case TransferRejections.ReplacementManagerNotEligible _ ->
          replacementNotEligible(LOCAL_MANAGER);
      case TransferRejections.NameConflict _ -> nameTaken();
      case TransferRejections.NoEligibleAdmin _ -> noEligibleAdmin();
    };
  }

  public static ForceDeleteProfileError toForceDeleteProfileError(
      TransferRejections.ForceDeleteProfile rejection) {
    return switch (rejection) {
      case TransferRejections.ProfileNotFound _ -> profileNotFound();
      case TransferRejections.ReasonRequired _ -> reasonRequired();
      case TransferRejections.ReauthenticationRequired _ -> reauthenticationRequired();
      case TransferRejections.ProfileLinked _ -> profileLinked();
    };
  }

  private static AccountNotFoundError accountNotFound() {
    return new AccountNotFoundError(NO_SUCH_ACCOUNT, InputPath.of(ACCOUNT_ID));
  }

  private static ProfileNotFoundError profileNotFound() {
    return new ProfileNotFoundError("No such Profile.", InputPath.of(PROFILE_ID));
  }

  private static HouseholdNotFoundError householdNotFound() {
    return new HouseholdNotFoundError("No such Household.", InputPath.of(DESTINATION));
  }

  private static SameHouseholdError sameHousehold() {
    return new SameHouseholdError(
        "The destination is already this Household.", InputPath.of(DESTINATION));
  }

  private static FinalAccountError finalAccount() {
    return new FinalAccountError("The final Account of a Household moves only through teardown.");
  }

  private static LastHouseholdAdminError lastHouseholdAdmin() {
    return new LastHouseholdAdminError("A Household with Accounts always keeps a HouseholdAdmin.");
  }

  private static LastServerAdminError lastServerAdmin() {
    return new LastServerAdminError("At least one enabled ServerAdmin remains.");
  }

  private static NoEligibleAdminError noEligibleAdmin() {
    return new NoEligibleAdminError(
        "A Household hosting a restricted Profile needs an eligible HouseholdAdmin.");
  }

  private static ProfileNameTakenError nameTaken() {
    return new ProfileNameTakenError(
        "Another available Profile there already uses that name.", InputPath.of(DESTINATION));
  }

  private static EligibleManagerRequiredError eligibleManagerRequired() {
    return new EligibleManagerRequiredError(
        "The Profile needs an eligible manager in its Household.");
  }

  private static ReplacementManagerNotEligibleError replacementNotEligible(String inputField) {
    return new ReplacementManagerNotEligibleError(
        "The manager must belong to the Profile's Household and have an unrestricted Adult Personal Profile.",
        InputPath.of(inputField));
  }

  private static ProfileLinkedError profileLinked() {
    return new ProfileLinkedError("A linked Profile moves or dies only with its Account.");
  }

  private static ReasonRequiredError reasonRequired() {
    return new ReasonRequiredError("Enter a reason for the audit record.", InputPath.of("reason"));
  }

  private static ReauthenticationRequiredError reauthenticationRequired() {
    return new ReauthenticationRequiredError("Confirm your password to continue.");
  }
}
