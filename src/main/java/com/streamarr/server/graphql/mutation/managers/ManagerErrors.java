package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.identity.ManagerRejections;

/** The exhaustive mappings from service rejection to schema error type, one per union. */
public final class ManagerErrors {

  private static final String PROFILE_ID = "profileId";
  private static final String CODE = "code";
  private static final String ACCOUNT_ID = "accountId";
  private static final String NO_SUCH_INVITATION = "No such invitation.";

  private ManagerErrors() {}

  public static InviteProfileManagerError toInviteError(ManagerRejections.Invite rejection) {
    return switch (rejection) {
      case ManagerRejections.ProfileNotFound _ -> profileNotFound();
      case ManagerRejections.RecipientNotFound _ -> recipientNotFound();
      case ManagerRejections.RecipientNotEligible _ -> recipientNotEligible();
      case ManagerRejections.AlreadyManager _ -> alreadyManager();
    };
  }

  public static CancelManagerInvitationError toCancelError(ManagerRejections.Cancel rejection) {
    return switch (rejection) {
      case ManagerRejections.ManagerInvitationNotFound _ ->
          new ManagerInvitationNotFoundError(NO_SUCH_INVITATION, InputPath.of("invitationId"));
      case ManagerRejections.InvitationNotPending _ ->
          new InvitationNotPendingError(
              "That invitation is not pending.", InputPath.of("invitationId"));
    };
  }

  public static AcceptManagerInvitationError toAcceptError(ManagerRejections.Accept rejection) {
    return switch (rejection) {
      case ManagerRejections.ManagerInvitationNotFound _ ->
          new ManagerInvitationNotFoundError(NO_SUCH_INVITATION, InputPath.of(CODE));
      case ManagerRejections.RecipientNotEligible _ -> recipientNotEligible();
      case ManagerRejections.AlreadyManager _ -> alreadyManager();
    };
  }

  public static DeclineManagerInvitationError toDeclineError(ManagerRejections.Decline rejection) {
    return switch (rejection) {
      case ManagerRejections.ManagerInvitationNotFound _ ->
          new ManagerInvitationNotFoundError(NO_SUCH_INVITATION, InputPath.of(CODE));
    };
  }

  public static RelinquishProfileManagementError toRelinquishError(
      ManagerRejections.Relinquish rejection) {
    return switch (rejection) {
      case ManagerRejections.ProfileNotFound _ -> profileNotFound();
      case ManagerRejections.ManagementAlreadyRemoved _ ->
          new ManagementAlreadyRemovedError("You no longer manage that Profile.");
      case ManagerRejections.ManagerAnchorRequired _ -> anchorRequired();
    };
  }

  public static RemoveProfileManagerError toRemoveError(ManagerRejections.Remove rejection) {
    return switch (rejection) {
      case ManagerRejections.ProfileNotFound _ -> profileNotFound();
      case ManagerRejections.NotAManager _ -> notAManager();
      case ManagerRejections.ManagerAnchorRequired _ -> anchorRequired();
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
      case ManagerRejections.ManagerAnchorRequired _ -> anchorRequired();
    };
  }

  private static ProfileNotFoundError profileNotFound() {
    return new ProfileNotFoundError("No such Profile.", InputPath.of(PROFILE_ID));
  }

  private static RecipientNotFoundError recipientNotFound() {
    return new RecipientNotFoundError("No such Account.", InputPath.of(ACCOUNT_ID));
  }

  private static RecipientNotEligibleError recipientNotEligible() {
    return new RecipientNotEligibleError(
        "A manager's own Personal Profile must be an unrestricted Adult.",
        InputPath.of(ACCOUNT_ID));
  }

  private static AlreadyManagerError alreadyManager() {
    return new AlreadyManagerError(
        "That Account already manages the Profile.", InputPath.of(ACCOUNT_ID));
  }

  private static NotAManagerError notAManager() {
    return new NotAManagerError(
        "That Account does not manage the Profile.", InputPath.of(ACCOUNT_ID));
  }

  private static ManagerAnchorRequiredError anchorRequired() {
    return new ManagerAnchorRequiredError(
        "Every Profile keeps its required home management anchor.");
  }

  private static ReasonRequiredError reasonRequired() {
    return new ReasonRequiredError("Enter a reason for the audit record.", InputPath.of("reason"));
  }

  private static ReauthenticationRequiredError reauthenticationRequired() {
    return new ReauthenticationRequiredError("Confirm your password to continue.");
  }
}
