package com.streamarr.server.services.identity;

/** Expected refusals of the ProfileManager mutations. */
public final class ManagerRejections {

  private ManagerRejections() {}

  /**
   * An invitation is refused only for what the inviter can see: the Profile, or the shape of the
   * address. Nothing about whether an Account has the address refuses it.
   */
  public sealed interface Invite
      permits ProfileNotFound, EmailRequired, EmailInvalid, ManagerInvitationLimitReached {}

  public sealed interface Cancel permits ManagerInvitationNotFound, InvitationNotPending {}

  /**
   * The named Account decides by the invitation's id. Anyone else, and an id that names no
   * invitation, get the same miss; only the named Account learns that an invitation was decided.
   */
  public sealed interface Accept
      permits ManagerInvitationNotFound,
          InvitationNotPending,
          RecipientNotEligible,
          AlreadyManager {}

  public sealed interface Decline permits ManagerInvitationNotFound, InvitationNotPending {}

  /** A code that does not work, and one that names another Account, get the same miss. */
  public sealed interface LookUp permits ManagerInvitationNotFound {}

  public sealed interface Relinquish
      permits ProfileNotFound, ManagementAlreadyRemoved, EligibleManagerRequired {}

  public sealed interface Remove permits ProfileNotFound, NotAManager, EligibleManagerRequired {}

  public sealed interface AdministrativelyGrant
      permits ProfileNotFound,
          ReasonRequired,
          ReauthenticationRequired,
          RecipientNotFound,
          RecipientNotEligible,
          AlreadyManager {}

  public sealed interface AdministrativelyRemove
      permits ProfileNotFound,
          ReasonRequired,
          ReauthenticationRequired,
          NotAManager,
          EligibleManagerRequired {}

  public record ProfileNotFound()
      implements Invite, Relinquish, Remove, AdministrativelyGrant, AdministrativelyRemove {}

  public record RecipientNotFound() implements AdministrativelyGrant {}

  public record EmailRequired() implements Invite {}

  public record EmailInvalid() implements Invite {}

  /** As many invitations as one Profile may have already wait for an answer. */
  public record ManagerInvitationLimitReached() implements Invite {}

  /** Eligible means the Account's own Personal Profile is an unrestricted Adult. */
  public record RecipientNotEligible() implements Accept, AdministrativelyGrant {}

  public record AlreadyManager() implements Accept, AdministrativelyGrant {}

  /**
   * The one deliberate answer for every miss: no such invitation, one the caller may not see, and a
   * code that does not work.
   */
  public record ManagerInvitationNotFound() implements Cancel, Accept, Decline, LookUp {}

  /** A visible invitation that was already decided, canceled or withdrawn, or that expired. */
  public record InvitationNotPending() implements Cancel, Accept, Decline {}

  public record ManagementAlreadyRemoved() implements Relinquish {}

  public record NotAManager() implements Remove, AdministrativelyRemove {}

  public record EligibleManagerRequired() implements Relinquish, Remove, AdministrativelyRemove {}

  public record ReasonRequired() implements AdministrativelyGrant, AdministrativelyRemove {}

  public record ReauthenticationRequired()
      implements AdministrativelyGrant, AdministrativelyRemove {}
}
