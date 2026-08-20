package com.streamarr.server.services.identity;

/** Expected refusals of the ProfileManager mutations (ADR 0026 shapes, ADR 0024 authority). */
public final class ManagerRejections {

  private ManagerRejections() {}

  public sealed interface Invite
      permits ProfileNotFound, RecipientNotFound, RecipientNotEligible, AlreadyManager {}

  public sealed interface Cancel permits ManagerInvitationNotFound, InvitationNotPending {}

  public sealed interface Accept
      permits ManagerInvitationNotFound, RecipientNotEligible, AlreadyManager {}

  public sealed interface Decline permits ManagerInvitationNotFound {}

  public sealed interface Relinquish
      permits ProfileNotFound, ManagementAlreadyRemoved, ManagerAnchorRequired {}

  public sealed interface Remove permits ProfileNotFound, NotAManager, ManagerAnchorRequired {}

  public sealed interface OverrideGrant
      permits ProfileNotFound,
          ReasonRequired,
          ReauthenticationRequired,
          RecipientNotFound,
          RecipientNotEligible,
          AlreadyManager {}

  public sealed interface OverrideRemove
      permits ProfileNotFound,
          ReasonRequired,
          ReauthenticationRequired,
          NotAManager,
          ManagerAnchorRequired {}

  public record ProfileNotFound()
      implements Invite, Relinquish, Remove, OverrideGrant, OverrideRemove {}

  public record RecipientNotFound() implements Invite, OverrideGrant {}

  /** Eligible means the Account's own Personal Profile is an unrestricted Adult (ADR 0024). */
  public record RecipientNotEligible() implements Invite, Accept, OverrideGrant {}

  public record AlreadyManager() implements Invite, Accept, OverrideGrant {}

  /** The one deliberate answer for every miss: unknown, expired, decided, or hidden. */
  public record ManagerInvitationNotFound() implements Cancel, Accept, Decline {}

  public record InvitationNotPending() implements Cancel {}

  public record ManagementAlreadyRemoved() implements Relinquish {}

  public record NotAManager() implements Remove, OverrideRemove {}

  /** T6: every Profile retains its required home management anchor. */
  public record ManagerAnchorRequired() implements Relinquish, Remove, OverrideRemove {}

  public record ReasonRequired() implements OverrideGrant, OverrideRemove {}

  public record ReauthenticationRequired() implements OverrideGrant, OverrideRemove {}
}
