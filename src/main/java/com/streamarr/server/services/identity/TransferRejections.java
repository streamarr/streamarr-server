package com.streamarr.server.services.identity;

/** Expected refusals of the transfer and deletion mutations. */
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
          EligibleManagerRequired,
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
          EligibleManagerRequired,
          NoEligibleAdmin {}

  public sealed interface DeleteMyAccount
      permits ConfirmationRequired,
          ReauthenticationRequired,
          FinalAccount,
          LastHouseholdAdmin,
          LastServerAdmin,
          EligibleManagerRequired,
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

  /** The final Account of a Household moves only through teardown. */
  public record FinalAccount() implements TransferAccount, DeleteAccount, DeleteMyAccount {}

  public record LastHouseholdAdmin() implements TransferAccount, DeleteAccount, DeleteMyAccount {}

  public record LastServerAdmin() implements DeleteAccount, DeleteMyAccount {}

  public record NoEligibleAdmin()
      implements TransferAccount, DeleteAccount, DeleteMyAccount, TransferProfile {}

  public record NameConflict() implements TransferAccount, TransferProfile {}

  public record EligibleManagerRequired()
      implements TransferAccount, DeleteAccount, DeleteMyAccount {}

  /** The first Account becomes HouseholdAdmin, and a restricted Account holds no authority. */
  public record RestrictedFirstAccount() implements TransferAccount {}

  /** Self-deletion is irreversible; the caller types the confirmation word deliberately. */
  public record ConfirmationRequired() implements DeleteMyAccount {}

  /** KEEP preserves the Profile only with an eligible replacement manager named up front. */
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
