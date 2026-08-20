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
          HomeAnchorRequired,
          ManagerNotEligible,
          LocalManagerNotFound {}

  public sealed interface RenameProfile
      permits ProfileNotFound, ProfileNameRequired, ProfileNameTaken {}

  public sealed interface SetProfilePicture permits ProfileNotFound {}

  public sealed interface ChangeProfilePolicy
      permits ProfileNotFound, ReauthenticationRequired, HomeAnchorRequired {}

  public sealed interface SetProfilePin permits ProfileNotFound, PinMalformed {}

  public sealed interface ClearProfilePin permits ProfileNotFound, WouldLockProfile {}

  public sealed interface OverrideProfilePin
      permits ProfileNotFound, PinMalformed, ReasonRequired, ReauthenticationRequired {}

  public sealed interface DeleteProfile
      permits ProfileNotFound, ProfileNotDeletable, ReauthenticationRequired {}

  public record ProfileNotFound()
      implements RenameProfile,
          SetProfilePicture,
          ChangeProfilePolicy,
          SetProfilePin,
          ClearProfilePin,
          OverrideProfilePin,
          DeleteProfile {}

  public record HouseholdNotFound() implements CreateProfile {}

  public record ProfileNameRequired() implements CreateProfile, RenameProfile {}

  /** T8: active Profile names are unique, ignoring case, within each Household. */
  public record ProfileNameTaken() implements CreateProfile, RenameProfile {}

  /** T6: every Profile keeps an eligible home anchor in the Household it belongs to. */
  public record HomeAnchorRequired() implements CreateProfile, ChangeProfilePolicy {}

  /** T5: an Account whose Personal Profile is restricted cannot hold manager authority. */
  public record ManagerNotEligible() implements CreateProfile {}

  public record LocalManagerNotFound() implements CreateProfile {}

  public record ReauthenticationRequired()
      implements ChangeProfilePolicy, OverrideProfilePin, DeleteProfile {}

  public record PinMalformed() implements SetProfilePin, OverrideProfilePin {}

  public record ReasonRequired() implements OverrideProfilePin {}

  /**
   * Clearing the PIN would lock the Profile where it is available. The Household is named only for
   * a caller who may view that Household's administration; everyone else learns just that some
   * Household's safety policy requires the PIN.
   */
  public record WouldLockProfile(UUID householdId, Optional<String> householdName)
      implements ClearProfilePin {}

  /** Deletion needs an unlinked, unshared Profile and the caller as sole remaining manager. */
  public record ProfileNotDeletable() implements DeleteProfile {}
}
