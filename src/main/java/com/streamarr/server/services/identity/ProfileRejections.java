package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.services.language.SetLanguagePreferencesRejection;
import java.util.Optional;
import lombok.NonNull;

/**
 * Every expected reason a Profile administration mutation refuses (ADR 0026): one sealed union per
 * mutation, members shared where mutations refuse for the same reason. Hidden resources refuse as
 * not-found to conceal their existence.
 */
public final class ProfileRejections {

  private ProfileRejections() {}

  public sealed interface CreateProfile
      permits HouseholdNotFound,
          ProfileNameRequired,
          ProfileNameTaken,
          EligibleManagerRequired,
          ManagerNotEligible,
          ProfileManagerNotEligible,
          MaximumAllowedRatingAgeInvalid {}

  /** How an ordinary edit refuses a caller that may not make it. */
  public sealed interface EditRefusal
      extends RenameProfile,
          SetProfilePicture,
          SetProfilePin,
          RemoveProfilePin,
          SetProfileLanguagePreferences
      permits ProfileNotFound, ManagersOnly {}

  public sealed interface RenameProfile
      permits EditRefusal, ProfileNameRequired, ProfileNameTaken {}

  public sealed interface SetProfilePicture permits EditRefusal {}

  public sealed interface ChangeProfilePolicy
      permits ProfileNotFound,
          ManagersOnly,
          ReauthenticationRequired,
          EligibleManagerRequired,
          RestrictedAccountAuthority,
          HostingHouseholdLacksEligibleAdmin,
          MaximumAllowedRatingAgeInvalid {}

  public sealed interface SetProfilePin permits EditRefusal, PinMalformed {}

  public sealed interface RemoveProfilePin permits EditRefusal, WouldLockProfile {}

  public sealed interface AdministrativelyResetProfilePin
      permits ProfileNotFound, PinMalformed, ReasonRequired, ReauthenticationRequired {}

  public sealed interface DeleteProfile
      permits ProfileNotFound, ManagersOnly, ProfileNotDeletable, ReauthenticationRequired {}

  public sealed interface SetProfileLanguagePreferences
      permits EditRefusal, PreferredAudioLanguagesRefused {}

  public record ProfileNotFound()
      implements EditRefusal, ChangeProfilePolicy, AdministrativelyResetProfilePin, DeleteProfile {}

  /**
   * Only the Profile's managers may do this, and the caller, who still reads the Profile as a
   * HouseholdAdmin where it is available, does not manage it; the reason says what it is to the
   * Profile now (ADR 0024 §HouseholdAdmin). It goes only to a caller that may read the Profile, so
   * it tells nothing that caller could not read.
   */
  public record ManagersOnly(@NonNull ManagersOnlyReason reason)
      implements EditRefusal, ChangeProfilePolicy, DeleteProfile {}

  /** What a caller refused with {@link ManagersOnly} is to the Profile now. */
  public enum ManagersOnlyReason {
    /** It supervises the restricted Profile there, and supervision does not cover this. */
    NOT_MANAGER,
    /** The Profile is unrestricted, so it administers the Profile there without supervising it. */
    NOT_RESTRICTED
  }

  /** The Profile may not keep the audio languages, for the reason the language rule gives. */
  public record PreferredAudioLanguagesRefused(@NonNull SetLanguagePreferencesRejection reason)
      implements SetProfileLanguagePreferences {}

  public record HouseholdNotFound() implements CreateProfile {}

  public record ProfileNameRequired() implements CreateProfile, RenameProfile {}

  public record ProfileNameTaken() implements CreateProfile, RenameProfile {}

  public record EligibleManagerRequired() implements CreateProfile, ChangeProfilePolicy {}

  public record ManagerNotEligible() implements CreateProfile {}

  public record RestrictedAccountAuthority() implements ChangeProfilePolicy {}

  /** Every Household hosting the Profile must keep an eligible HouseholdAdmin once restricted. */
  public record HostingHouseholdLacksEligibleAdmin() implements ChangeProfilePolicy {}

  public record MaximumAllowedRatingAgeInvalid() implements CreateProfile, ChangeProfilePolicy {}

  public record ProfileManagerNotEligible() implements CreateProfile {}

  public record ReauthenticationRequired()
      implements ChangeProfilePolicy, AdministrativelyResetProfilePin, DeleteProfile {}

  public record PinMalformed() implements SetProfilePin, AdministrativelyResetProfilePin {}

  public record ReasonRequired() implements AdministrativelyResetProfilePin {}

  /**
   * Removing the PIN would lock the Profile where it is available. The Household is named only for
   * a caller who may already see that the Profile is available there: one that may use it, manages
   * the Profile, or is a ServerAdmin (ADR 0024 §PIN safety). Everyone else learns just that another
   * Household's safety policy requires the PIN.
   */
  public record WouldLockProfile(@NonNull Optional<Household> household)
      implements RemoveProfilePin {}

  /** Deletion needs an unlinked, unshared Profile and the caller as sole remaining manager. */
  public record ProfileNotDeletable() implements DeleteProfile {}
}
