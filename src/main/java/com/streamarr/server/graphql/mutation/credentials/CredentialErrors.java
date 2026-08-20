package com.streamarr.server.graphql.mutation.credentials;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.identity.InvitationRejections;

/** The exhaustive mappings from service rejection to schema error type, one per union. */
public final class CredentialErrors {

  private CredentialErrors() {}

  public static IssueAccountInvitationError toIssueError(InvitationRejections.Issue rejection) {
    return switch (rejection) {
      case InvitationRejections.EmailRequired _ ->
          new EmailRequiredError("Enter the recipient's email.", InputPath.of("recipientEmail"));
      case InvitationRejections.EmailAlreadyUsed _ ->
          new EmailAlreadyUsedError(
              "An Account already uses that email; transfer it instead.",
              InputPath.of("recipientEmail"));
      case InvitationRejections.ProfileNameRequired _ ->
          new ProfileNameRequiredError("Enter a profile name.", InputPath.of("profileName"));
      case InvitationRejections.HouseholdNotFound _ ->
          new HouseholdNotFoundError("No such Household.", InputPath.of("householdId"));
      case InvitationRejections.RestrictedFirstAccount _ ->
          new RestrictedFirstAccountError(
              "The first Account of an empty Household becomes HouseholdAdmin; it cannot be"
                  + " restricted.");
      case InvitationRejections.LocalManagerRequired _ ->
          new LocalManagerRequiredError(
              "A restricted Profile needs an eligible local manager.",
              InputPath.of("localManagerAccountId"));
      case InvitationRejections.LocalManagerNotFound _ ->
          new LocalManagerNotFoundError("No such Account.", InputPath.of("localManagerAccountId"));
    };
  }

  public static CancelAccountInvitationError toCancelError(InvitationRejections.Cancel rejection) {
    return switch (rejection) {
      case InvitationRejections.InvitationNotPending _ ->
          new InvitationNotPendingError(
              "That invitation is not pending.", InputPath.of("invitationId"));
    };
  }

  public static IssuePasswordResetError toIssueResetError(
      InvitationRejections.IssueReset rejection) {
    return switch (rejection) {
      case InvitationRejections.AccountNotFound _ ->
          new AccountNotFoundError("No such Account.", InputPath.of("accountId"));
      case InvitationRejections.ReasonRequired _ ->
          new ReasonRequiredError("Enter a reason for the audit record.", InputPath.of("reason"));
      case InvitationRejections.ReauthenticationRequired _ ->
          new ReauthenticationRequiredError("Confirm your password to continue.");
    };
  }
}
