package com.streamarr.server.services.identity;

/** Expected refusals of the sharing mutations (ADR 0026). */
public final class ShareRejections {

  private ShareRejections() {}

  public sealed interface Offer permits ProfileNotFound, HouseholdNotFound, AlreadyShared {}

  public sealed interface Decide
      permits ShareNotFound, ShareNotPending, NoEligibleAdmin, NameConflict {}

  public sealed interface End
      permits ShareNotFound,
          ShareNotActive,
          StructuralShareCannotEnd,
          ReauthenticationRequired,
          ReasonRequired {}

  public record ProfileNotFound() implements Offer {}

  public record HouseholdNotFound() implements Offer {}

  /** One live (pending or active) share per Profile and Household. */
  public record AlreadyShared() implements Offer {}

  public record ShareNotFound() implements Decide, End {}

  public record ShareNotPending() implements Decide {}

  /** T7: a Household hosting a restricted Profile holds an eligible HouseholdAdmin. */
  public record NoEligibleAdmin() implements Decide {}

  /** T8: the Profile's name collides with another available Profile there. */
  public record NameConflict() implements Decide {}

  public record ShareNotActive() implements End {}

  /** T3: nobody ends a structural share while the Account remains a member. */
  public record StructuralShareCannotEnd() implements End {}

  public record ReauthenticationRequired() implements End {}

  public record ReasonRequired() implements End {}
}
