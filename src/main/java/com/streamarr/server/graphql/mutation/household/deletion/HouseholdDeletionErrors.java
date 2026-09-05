package com.streamarr.server.graphql.mutation.household.deletion;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.graphql.mutation.MutationError;
import com.streamarr.server.services.identity.HouseholdDeletionRejections;

/** Exhaustive service-rejection mappings for the four Household deletion actions. */
public final class HouseholdDeletionErrors {

  private HouseholdDeletionErrors() {}

  public static DeleteEmptyHouseholdError toDeleteEmptyHouseholdError(
      HouseholdDeletionRejections.Delete rejection) {
    var error = toError(rejection);
    if (error instanceof DeleteEmptyHouseholdError allowed) {
      return allowed;
    }

    throw unexpected(rejection);
  }

  public static TransferLastAccountAndDeleteHouseholdError
      toTransferLastAccountAndDeleteHouseholdError(HouseholdDeletionRejections.Delete rejection) {
    var error = toError(rejection);
    if (error instanceof TransferLastAccountAndDeleteHouseholdError allowed) {
      return allowed;
    }

    throw unexpected(rejection);
  }

  public static DeleteLastAccountAndHouseholdError toDeleteLastAccountAndHouseholdError(
      HouseholdDeletionRejections.Delete rejection) {
    var error = toError(rejection);
    if (error instanceof DeleteLastAccountAndHouseholdError allowed) {
      return allowed;
    }

    throw unexpected(rejection);
  }

  public static DeleteLastAccountAndHouseholdPreservingPersonalProfileError
      toDeleteLastAccountAndHouseholdPreservingPersonalProfileError(
          HouseholdDeletionRejections.Delete rejection) {
    var error = toError(rejection);
    if (error instanceof DeleteLastAccountAndHouseholdPreservingPersonalProfileError allowed) {
      return allowed;
    }

    throw unexpected(rejection);
  }

  public static InvalidIdError invalidId(String inputName) {
    return new InvalidIdError("Enter a valid ID.", InputPath.of(inputName));
  }

  private static MutationError toError(HouseholdDeletionRejections.Delete rejection) {
    return switch (rejection) {
      case HouseholdDeletionRejections.HouseholdNotFound _ ->
          new HouseholdNotFoundError("No such Household.", InputPath.of("householdId"));
      case HouseholdDeletionRejections.ReasonRequired _ ->
          new ReasonRequiredError("Enter a reason for the audit record.", InputPath.of("reason"));
      case HouseholdDeletionRejections.ReauthenticationRequired _ ->
          new ReauthenticationRequiredError("Confirm your password before retrying this action.");
      case HouseholdDeletionRejections.AccountsRemain _ ->
          new AccountsRemainError(
              "Every other Account must already have been transferred or deleted.");
      case HouseholdDeletionRejections.LastAccountNotFound _ ->
          new LastAccountNotFoundError("The Household has no Account to dispose of.");
      case HouseholdDeletionRejections.DestinationNotFound _ ->
          new DestinationNotFoundError(
              "No such Household.", InputPath.of("destinationHouseholdId"));
      case HouseholdDeletionRejections.ReplacementManagerNotFound _ ->
          new AccountNotFoundError("No such Account.", InputPath.of("replacementManagerAccountId"));
      case HouseholdDeletionRejections.ReplacementManagerNotEligible _ ->
          new ProfileManagerNotEligibleError(
              "That Account cannot manage the Profile because it is outside the destination Household or its Personal Profile is restricted.",
              InputPath.of("replacementManagerAccountId"));
      case HouseholdDeletionRejections.LastServerAdmin _ ->
          new LastServerAdminError("At least one enabled ServerAdmin remains.");
    };
  }

  private static IllegalStateException unexpected(HouseholdDeletionRejections.Delete rejection) {
    return new IllegalStateException(
        "Unexpected Household deletion rejection: " + rejection.getClass().getSimpleName());
  }
}
