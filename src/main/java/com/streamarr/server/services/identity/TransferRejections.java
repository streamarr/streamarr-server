package com.streamarr.server.services.identity;

/** Expected refusals of the transfer and deletion mutations (ADR 0026 shapes). */
public final class TransferRejections {

  private TransferRejections() {}

  public sealed interface TransferAccount
      permits AccountNotFound,
          HouseholdNotFound,
          SameHousehold,
          FinalAccount,
          LastHouseholdAdmin,
          NoEligibleAdmin,
          NameConflict,
          AnchorRequired,
          RestrictedFirstAccount {}

  public sealed interface DeleteAccount
      permits AccountNotFound,
          ReasonRequired,
          ReauthenticationRequired,
          FinalAccount,
          LastHouseholdAdmin,
          LastServerAdmin,
          ReplacementManagerRequired,
          ReplacementManagerNotFound,
          ReplacementManagerNotEligible,
          AnchorRequired,
          NoEligibleAdmin {}

  public sealed interface DeleteMyAccount
      permits ConfirmationRequired,
          ReauthenticationRequired,
          FinalAccount,
          LastHouseholdAdmin,
          LastServerAdmin,
          AnchorRequired,
          NoEligibleAdmin {}

  public sealed interface TransferProfile
      permits ProfileNotFound,
          HouseholdNotFound,
          SameHousehold,
          ProfileLinked,
          LocalManagerRequired,
          LocalManagerNotFound,
          ReplacementManagerNotEligible,
          NameConflict,
          NoEligibleAdmin {}

  public sealed interface ForceDeleteProfile
      permits ProfileNotFound, ReasonRequired, ReauthenticationRequired, ProfileLinked {}

  public record AccountNotFound() implements TransferAccount, DeleteAccount {}

  public record ProfileNotFound() implements TransferProfile, ForceDeleteProfile {}

  public record HouseholdNotFound() implements TransferAccount, TransferProfile {}

  public record SameHousehold() implements TransferAccount, TransferProfile {}

  /** The final Account of a Household moves only through teardown (ADR 0024). */
  public record FinalAccount() implements TransferAccount, DeleteAccount, DeleteMyAccount {}

  /** T1: after its first Account, a Household always keeps a HouseholdAdmin. */
  public record LastHouseholdAdmin() implements TransferAccount, DeleteAccount, DeleteMyAccount {}

  /** T4: after bootstrap, at least one enabled ServerAdmin remains. */
  public record LastServerAdmin() implements DeleteAccount, DeleteMyAccount {}

  /** T7: a Household hosting a restricted Profile keeps an eligible HouseholdAdmin. */
  public record NoEligibleAdmin()
      implements TransferAccount, DeleteAccount, DeleteMyAccount, TransferProfile {}

  /** T8: active Profile names stay unique within the destination Household. */
  public record NameConflict() implements TransferAccount, TransferProfile {}

  /** T6: every Profile keeps its required home management anchor. */
  public record AnchorRequired() implements TransferAccount, DeleteAccount, DeleteMyAccount {}

  /** The first Account becomes HouseholdAdmin, and a restricted Account holds no authority. */
  public record RestrictedFirstAccount() implements TransferAccount {}

  /** Self-deletion is irreversible; the caller types the confirmation word deliberately. */
  public record ConfirmationRequired() implements DeleteMyAccount {}

  /** KEEP preserves the Profile only with a valid replacement anchor named up front. */
  public record ReplacementManagerRequired() implements DeleteAccount {}

  public record ReplacementManagerNotFound() implements DeleteAccount {}

  public record ReplacementManagerNotEligible() implements DeleteAccount, TransferProfile {}

  public record LocalManagerRequired() implements TransferProfile {}

  public record LocalManagerNotFound() implements TransferProfile {}

  public record ProfileLinked() implements TransferProfile, ForceDeleteProfile {}

  public record ReasonRequired() implements DeleteAccount, ForceDeleteProfile {}

  public record ReauthenticationRequired()
      implements DeleteAccount, DeleteMyAccount, ForceDeleteProfile {}
}
