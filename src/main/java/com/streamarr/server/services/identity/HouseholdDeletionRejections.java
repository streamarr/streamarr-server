package com.streamarr.server.services.identity;

/** Expected refusals of Household deletion (ADR 0026 shapes). */
public final class HouseholdDeletionRejections {

  private HouseholdDeletionRejections() {}

  public sealed interface Delete
      permits HouseholdNotFound,
          ReasonRequired,
          ReauthenticationRequired,
          AccountsRemain,
          LastAccountNotFound,
          DestinationNotFound,
          ReplacementManagerNotFound,
          ReplacementManagerNotEligible,
          LastServerAdmin {}

  public record HouseholdNotFound() implements Delete {}

  public record ReasonRequired() implements Delete {}

  public record ReauthenticationRequired() implements Delete {}

  /** Every other Account must already have been transferred or deleted (ADR 0024). */
  public record AccountsRemain() implements Delete {}

  /** A final-Account action requires exactly one resident Account. */
  public record LastAccountNotFound() implements Delete {}

  public record DestinationNotFound() implements Delete {}

  public record ReplacementManagerNotFound() implements Delete {}

  public record ReplacementManagerNotEligible() implements Delete {}

  /** T4: after bootstrap, at least one enabled ServerAdmin remains. */
  public record LastServerAdmin() implements Delete {}
}
