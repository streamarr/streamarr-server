package com.streamarr.server.services.identity;

/** Expected refusals of the sharing mutations, one sealed set per verb (ADR 0026). */
public final class ShareRejections {

  private ShareRejections() {}

  public sealed interface Offer permits ProfileNotFound, HouseholdNotFound, AlreadyShared {}

  public sealed interface Accept
      permits ShareNotFound, ShareNotPending, NoEligibleAdmin, NameConflict {}

  /** Reject and cancel share one set: both only move a PENDING offer aside. */
  public sealed interface Decline permits ShareNotFound, ShareNotPending {}

  public sealed interface End permits ShareNotFound, ShareNotActive, StructuralShareCannotEnd {}

  /** The audited ServerAdmin end adds the reason and fresh-reauthentication requirements. */
  public sealed interface ForceEnd
      permits ShareNotFound,
          ShareNotActive,
          StructuralShareCannotEnd,
          ReauthenticationRequired,
          ReasonRequired {}

  public record ProfileNotFound() implements Offer {}

  public record HouseholdNotFound() implements Offer {}

  /**
   * The Profile is already available in that Household; a pending offer is replaced, not refused.
   */
  public record AlreadyShared() implements Offer {}

  public record ShareNotFound() implements Accept, Decline, End, ForceEnd {}

  public record ShareNotPending() implements Accept, Decline {}

  /** T7: a Household hosting a restricted Profile holds an eligible HouseholdAdmin. */
  public record NoEligibleAdmin() implements Accept {}

  /** T8: the Profile's name collides with another available Profile there. */
  public record NameConflict() implements Accept {}

  public record ShareNotActive() implements End, ForceEnd {}

  /** T3: nobody ends a structural share while the Account remains a member. */
  public record StructuralShareCannotEnd() implements End, ForceEnd {}

  public record ReauthenticationRequired() implements ForceEnd {}

  public record ReasonRequired() implements ForceEnd {}
}
