package com.streamarr.server.services.identity;

/** Expected refusals of Household teardown (ADR 0026 shapes). */
public final class TeardownRejections {

  private TeardownRejections() {}

  public sealed interface TearDown
      permits HouseholdNotFound,
          ReasonRequired,
          ReauthenticationRequired,
          AccountsRemain,
          FinalAccountRequired,
          FinalAccountUnexpected,
          DestinationRequired,
          DestinationNotFound,
          ReplacementManagerRequired,
          ReplacementManagerNotFound,
          ReplacementManagerNotEligible,
          LastServerAdmin {}

  public record HouseholdNotFound() implements TearDown {}

  public record ReasonRequired() implements TearDown {}

  public record ReauthenticationRequired() implements TearDown {}

  /** Every other Account must already have been transferred or deleted (ADR 0024). */
  public record AccountsRemain() implements TearDown {}

  /** One Account remains, so the caller chooses its atomic disposition. */
  public record FinalAccountRequired() implements TearDown {}

  /** The Household is already empty of Accounts; there is nothing to dispose of. */
  public record FinalAccountUnexpected() implements TearDown {}

  /** TRANSFER and the preserved-Profile move both name a destination Household. */
  public record DestinationRequired() implements TearDown {}

  public record DestinationNotFound() implements TearDown {}

  public record ReplacementManagerRequired() implements TearDown {}

  public record ReplacementManagerNotFound() implements TearDown {}

  public record ReplacementManagerNotEligible() implements TearDown {}

  /** T4: after bootstrap, at least one enabled ServerAdmin remains. */
  public record LastServerAdmin() implements TearDown {}
}
