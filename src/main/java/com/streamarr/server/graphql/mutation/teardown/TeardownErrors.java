package com.streamarr.server.graphql.mutation.teardown;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.identity.TeardownRejections;
import java.util.List;

/** The exhaustive mapping from service rejection to schema error type. */
public final class TeardownErrors {

  private static final List<String> DESTINATION =
      InputPath.of("lastAccount", "destinationHouseholdId");
  private static final List<String> REPLACEMENT =
      InputPath.of("lastAccount", "replacementManagerAccountId");

  private TeardownErrors() {}

  public static TearDownHouseholdError toTearDownError(TeardownRejections.TearDown rejection) {
    return switch (rejection) {
      case TeardownRejections.HouseholdNotFound _ ->
          new HouseholdNotFoundError("No such Household.", InputPath.of("householdId"));
      case TeardownRejections.ReasonRequired _ ->
          new ReasonRequiredError("Enter a reason for the audit record.", InputPath.of("reason"));
      case TeardownRejections.ReauthenticationRequired _ ->
          new ReauthenticationRequiredError("Confirm your password before retrying this action.");
      case TeardownRejections.AccountsRemain _ ->
          new AccountsRemainError(
              "Every other Account must already have been transferred or deleted.");
      case TeardownRejections.FinalAccountRequired _ ->
          new LastAccountActionRequiredError(
              "One Account remains; choose whether to transfer or delete it.");
      case TeardownRejections.FinalAccountUnexpected _ ->
          new LastAccountActionNotAllowedError(
              "The Household has no Accounts, so lastAccount must be omitted.");
      case TeardownRejections.DestinationRequired _ ->
          new DestinationRequiredError("Name the destination Household.", DESTINATION);
      case TeardownRejections.DestinationNotFound _ ->
          new DestinationNotFoundError("No such Household.", DESTINATION);
      case TeardownRejections.ReplacementManagerRequired _ ->
          new ReplacementManagerRequiredError(
              "Keeping the Profile requires a replacement Profile manager.", REPLACEMENT);
      case TeardownRejections.ReplacementManagerNotFound _ ->
          new AccountNotFoundError("No such Account.", REPLACEMENT);
      case TeardownRejections.ReplacementManagerNotEligible _ ->
          new ProfileManagerNotEligibleError(
              "That Account cannot manage the Profile because it is outside the destination Household or its Personal Profile is restricted.",
              REPLACEMENT);
      case TeardownRejections.LastServerAdmin _ ->
          new LastServerAdminError("At least one enabled ServerAdmin remains.");
    };
  }
}
