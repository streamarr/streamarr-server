package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.identity.ManagerRejections;

/** The exhaustive mappings from service rejection to schema error type, one per union. */
public final class ManagerErrors {

  private static final String PROFILE_ID = "profileId";
  private static final String CODE = "code";
  private static final String ACCOUNT_ID = "accountId";
  private static final String RECIPIENT_ACCOUNT_ID = "recipientAccountId";
  private static final String INVITATION_UNAVAILABLE = "That invitation is unavailable.";

  private ManagerErrors() {}

  public static InviteProfileManagerError toInviteError(ManagerRejections.Invite rejection) {
    return switch (rejection) {
      case ManagerRejections.ProfileNotFound _ -> profileNotFound();
      case ManagerRejections.RecipientNotFound _ -> recipientNotFound(RECIPIENT_ACCOUNT_ID);
      case ManagerRejections.RecipientNotEligible _ -> recipientNotEligible(RECIPIENT_ACCOUNT_ID);
      case ManagerRejections.AlreadyManager _ -> alreadyManager(RECIPIENT_ACCOUNT_ID);
    };
  }

  public static CancelManagerInvitationError toCancelError(ManagerRejections.Cancel rejection) {
    return switch (rejection) {
      case ManagerRejections.ManagerInvitationNotFound _ ->
          new ManagerInvitationNotFoundError(INVITATION_UNAVAILABLE, InputPath.of("invitationId"));
      case ManagerRejections.InvitationNotPending _ ->
          new InvitationNotPendingError(INVITATION_UNAVAILABLE, InputPath.of("invitationId"));
    };
  }

  public static AcceptManagerInvitationError toAcceptError(ManagerRejections.Accept rejection) {
    return switch (rejection) {
      case ManagerRejections.ManagerInvitationNotFound _ ->
          new ManagerInvitationNotFoundError(INVITATION_UNAVAILABLE, InputPath.of(CODE));
      case ManagerRejections.RecipientNotEligible _ -> recipientNotEligible(CODE);
      case ManagerRejections.AlreadyManager _ -> alreadyManager(CODE);
    };
  }

  public static DeclineManagerInvitationError toDeclineError(ManagerRejections.Decline rejection) {
    return switch (rejection) {
      case ManagerRejections.ManagerInvitationNotFound _ ->
          new ManagerInvitationNotFoundError(INVITATION_UNAVAILABLE, InputPath.of(CODE));
    };
  }

  public static RelinquishProfileManagementError toRelinquishError(
      ManagerRejections.Relinquish rejection) {
    return switch (rejection) {
      case ManagerRejections.ProfileNotFound _ -> profileNotFound();
      case ManagerRejections.ManagementAlreadyRemoved _ ->
          new ManagementAlreadyRemovedError("You no longer manage that Profile.");
      case ManagerRejections.EligibleManagerRequired _ -> eligibleManagerRequired();
    };
  }

  public static RemoveProfileManagerError toRemoveError(ManagerRejections.Remove rejection) {
    return switch (rejection) {
      case ManagerRejections.ProfileNotFound _ -> profileNotFound();
      case ManagerRejections.NotAManager _ -> notAManager();
      case ManagerRejections.EligibleManagerRequired _ -> eligibleManagerRequired();
    };
  }

  public static GrantProfileManagerOverrideError toGrantOverrideError(
      ManagerRejections.OverrideGrant rejection) {
    return switch (rejection) {
      case ManagerRejections.ProfileNotFound _ -> profileNotFound();
      case ManagerRejections.ReasonRequired _ -> reasonRequired();
      case ManagerRejections.ReauthenticationRequired _ -> reauthenticationRequired();
      case ManagerRejections.RecipientNotFound _ -> recipientNotFound();
      case ManagerRejections.RecipientNotEligible _ -> recipientNotEligible();
      case ManagerRejections.AlreadyManager _ -> alreadyManager();
    };
  }

  public static RemoveProfileManagerOverrideError toRemoveOverrideError(
      ManagerRejections.OverrideRemove rejection) {
    return switch (rejection) {
      case ManagerRejections.ProfileNotFound _ -> profileNotFound();
      case ManagerRejections.ReasonRequired _ -> reasonRequired();
      case ManagerRejections.ReauthenticationRequired _ -> reauthenticationRequired();
      case ManagerRejections.NotAManager _ -> notAManager();
      case ManagerRejections.EligibleManagerRequired _ -> eligibleManagerRequired();
    };
  }

  private static ProfileNotFoundError profileNotFound() {
    return new ProfileNotFoundError("No such Profile.", InputPath.of(PROFILE_ID));
  }

  private static AccountNotFoundError recipientNotFound() {
    return recipientNotFound(ACCOUNT_ID);
  }

  private static AccountNotFoundError recipientNotFound(String inputPath) {
    return new AccountNotFoundError("No such Account.", InputPath.of(inputPath));
  }

  private static ProfileManagerNotEligibleError recipientNotEligible() {
    return recipientNotEligible(ACCOUNT_ID);
  }

  private static ProfileManagerNotEligibleError recipientNotEligible(String inputPath) {
    return new ProfileManagerNotEligibleError(
        "That Account cannot manage Profiles because its Personal Profile is restricted.",
        InputPath.of(inputPath));
  }

  private static AlreadyManagerError alreadyManager() {
    return alreadyManager(ACCOUNT_ID);
  }

  private static AlreadyManagerError alreadyManager(String inputPath) {
    return new AlreadyManagerError(
        "That Account already manages the Profile.", InputPath.of(inputPath));
  }

  private static NotAManagerError notAManager() {
    return new NotAManagerError(
        "That Account does not manage the Profile.", InputPath.of(ACCOUNT_ID));
  }

  private static ProfileRequiresEligibleManagerError eligibleManagerRequired() {
    return new ProfileRequiresEligibleManagerError(
        "The Profile needs an eligible Profile manager in its Household.");
  }

  private static ReasonRequiredError reasonRequired() {
    return new ReasonRequiredError("Enter a reason for the audit record.", InputPath.of("reason"));
  }

  private static ReauthenticationRequiredError reauthenticationRequired() {
    return new ReauthenticationRequiredError("Confirm your password before retrying this action.");
  }
}
