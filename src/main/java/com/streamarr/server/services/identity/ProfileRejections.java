package com.streamarr.server.services.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * Every expected reason a Profile administration mutation refuses (ADR 0026): one sealed union per
 * mutation, members shared where mutations refuse for the same reason. Hidden resources refuse as
 * not-found under the oracle rule.
 */
public final class ProfileRejections {

  private ProfileRejections() {}

  public sealed interface CreateProfile
      permits HouseholdNotFound,
          ProfileNameRequired,
          ProfileNameTaken,
          EligibleManagerRequired,
          ManagerNotEligible,
          LocalManagerNotFound,
          MaximumAllowedRatingAgeInvalid {}

  public sealed interface RenameProfile
      permits ProfileNotFound, ProfileNameRequired, ProfileNameTaken {}

  public sealed interface SetProfilePicture permits ProfileNotFound {}

  public sealed interface ChangeProfilePolicy
      permits ProfileNotFound,
          ReauthenticationRequired,
          EligibleManagerRequired,
          RestrictedAccountAuthority,
          MaximumAllowedRatingAgeInvalid {}

  public sealed interface SetProfilePin permits ProfileNotFound, PinMalformed {}

  public sealed interface RemoveProfilePin permits ProfileNotFound, WouldLockProfile {}

  public sealed interface OverrideProfilePin
      permits ProfileNotFound, PinMalformed, ReasonRequired, ReauthenticationRequired {}

  public sealed interface DeleteProfile
      permits ProfileNotFound, ProfileNotDeletable, ReauthenticationRequired {}

  public record ProfileNotFound()
      implements RenameProfile,
          SetProfilePicture,
          ChangeProfilePolicy,
          SetProfilePin,
          RemoveProfilePin,
          OverrideProfilePin,
          DeleteProfile {}

  public record HouseholdNotFound() implements CreateProfile {}

  public record ProfileNameRequired() implements CreateProfile, RenameProfile {}

  public record ProfileNameTaken() implements CreateProfile, RenameProfile {}

  public record EligibleManagerRequired() implements CreateProfile, ChangeProfilePolicy {}

  public record ManagerNotEligible() implements CreateProfile {}

  public record RestrictedAccountAuthority() implements ChangeProfilePolicy {}

  public record MaximumAllowedRatingAgeInvalid() implements CreateProfile, ChangeProfilePolicy {}

  public record LocalManagerNotFound() implements CreateProfile {}

  public record ReauthenticationRequired()
      implements ChangeProfilePolicy, OverrideProfilePin, DeleteProfile {}

  public record PinMalformed() implements SetProfilePin, OverrideProfilePin {}

  public record ReasonRequired() implements OverrideProfilePin {}

  /**
   * Removing the PIN would lock the Profile where it is available. The Household is named only for
   * a caller who may view that Household's administration; everyone else learns just that some
   * Household's safety policy requires the PIN.
   */
  public record WouldLockProfile(UUID householdId, Optional<String> householdName)
      implements RemoveProfilePin {}

  /** Deletion needs an unlinked, unshared Profile and the caller as sole remaining manager. */
  public record ProfileNotDeletable() implements DeleteProfile {}
}
