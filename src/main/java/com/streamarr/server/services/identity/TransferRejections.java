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

  public sealed interface AdministrativelyDeleteAccount
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

  public sealed interface AdministrativelyDeleteProfile
      permits ProfileNotFound, ReasonRequired, ReauthenticationRequired, ProfileLinked {}

  public record AccountNotFound() implements TransferAccount, AdministrativelyDeleteAccount {}

  public record ProfileNotFound() implements TransferProfile, AdministrativelyDeleteProfile {}

  public record HouseholdNotFound() implements TransferAccount, TransferProfile {}

  public record SameHousehold() implements TransferAccount, TransferProfile {}

  /** The final Account of a Household moves only through teardown. */
  public record FinalAccount()
      implements TransferAccount, AdministrativelyDeleteAccount, DeleteMyAccount {}

  public record LastHouseholdAdmin()
      implements TransferAccount, AdministrativelyDeleteAccount, DeleteMyAccount {}

  public record LastServerAdmin() implements AdministrativelyDeleteAccount, DeleteMyAccount {}

  public record NoEligibleAdmin()
      implements TransferAccount, AdministrativelyDeleteAccount, DeleteMyAccount, TransferProfile {}

  public record NameConflict() implements TransferAccount, TransferProfile {}

  public record EligibleManagerRequired()
      implements TransferAccount, AdministrativelyDeleteAccount, DeleteMyAccount {}

  /** The first Account becomes HouseholdAdmin, and a restricted Account holds no authority. */
  public record RestrictedFirstAccount() implements TransferAccount {}

  /** Self-deletion is irreversible; the caller types the confirmation word deliberately. */
  public record ConfirmationRequired() implements DeleteMyAccount {}

  /** KEEP preserves the Profile only with an eligible replacement manager named up front. */
  public record ReplacementManagerRequired() implements AdministrativelyDeleteAccount {}

  public record ReplacementManagerNotFound() implements AdministrativelyDeleteAccount {}

  public record ReplacementManagerNotEligible()
      implements AdministrativelyDeleteAccount, TransferProfile {}

  public record LocalManagerRequired() implements TransferProfile {}

  public record LocalManagerNotFound() implements TransferProfile {}

  public record ProfileLinked() implements TransferProfile, AdministrativelyDeleteProfile {}

  public record ReasonRequired()
      implements AdministrativelyDeleteAccount, AdministrativelyDeleteProfile {}

  public record ReauthenticationRequired()
      implements AdministrativelyDeleteAccount, DeleteMyAccount, AdministrativelyDeleteProfile {}
}
