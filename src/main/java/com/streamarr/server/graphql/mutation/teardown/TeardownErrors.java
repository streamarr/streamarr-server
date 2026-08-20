package com.streamarr.server.graphql.mutation.teardown;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.identity.TeardownRejections;

/** The exhaustive mapping from service rejection to schema error type. */
public final class TeardownErrors {

  private static final String DESTINATION = "finalAccount.destinationHouseholdId";
  private static final String REPLACEMENT = "finalAccount.replacementManagerAccountId";

  private TeardownErrors() {}

  public static TearDownHouseholdError toTearDownError(TeardownRejections.TearDown rejection) {
    return switch (rejection) {
      case TeardownRejections.HouseholdNotFound _ ->
          new HouseholdNotFoundError("No such Household.", InputPath.of("householdId"));
      case TeardownRejections.ReasonRequired _ ->
          new ReasonRequiredError("Enter a reason for the audit record.", InputPath.of("reason"));
      case TeardownRejections.ReauthenticationRequired _ ->
          new ReauthenticationRequiredError("Confirm your password to continue.");
      case TeardownRejections.AccountsRemain _ ->
          new AccountsRemainError(
              "Every other Account must already have been transferred or deleted.");
      case TeardownRejections.FinalAccountRequired _ ->
          new FinalAccountRequiredError("One Account remains; choose its atomic disposition.");
      case TeardownRejections.FinalAccountUnexpected _ ->
          new FinalAccountUnexpectedError(
              "The Household has no Accounts; there is nothing to dispose of.");
      case TeardownRejections.DestinationRequired _ ->
          new DestinationRequiredError(
              "Name the destination Household.", InputPath.of(DESTINATION));
      case TeardownRejections.DestinationNotFound _ ->
          new DestinationNotFoundError("No such Household.", InputPath.of(DESTINATION));
      case TeardownRejections.ReplacementManagerRequired _ ->
          new ReplacementManagerRequiredError(
              "KEEP preserves the Profile only with a replacement manager named up front.",
              InputPath.of(REPLACEMENT));
      case TeardownRejections.ReplacementManagerNotFound _ ->
          new ReplacementManagerNotFoundError("No such Account.", InputPath.of(REPLACEMENT));
      case TeardownRejections.ReplacementManagerNotEligible _ ->
          new ReplacementManagerNotEligibleError(
              "The anchor lives in the destination Household and is themselves an unrestricted"
                  + " Adult.",
              InputPath.of(REPLACEMENT));
      case TeardownRejections.LastServerAdmin _ ->
          new LastServerAdminError("At least one enabled ServerAdmin remains.");
    };
  }
}
