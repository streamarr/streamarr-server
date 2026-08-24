package com.streamarr.server.services.authorization;

import com.streamarr.server.domain.auth.ProfileKind;
import java.util.UUID;

/**
 * What a caller wants to do, in domain terms. The authorization module maps an intent to the Cedar
 * action, resource, required facts, and attempt context itself; callers never choose an action,
 * assemble entities, or supply their own reading of authority. {@code T} is the value an allowed
 * decision carries back — normalized values the mutation must then write, or {@link
 * AuthorizationUnit} when there is nothing to return.
 */
// java:S2326: T is the type witness for what an allowed decision returns; AuthorizationService
// signatures consume it even though no member of the interface does.
@SuppressWarnings("java:S2326")
public sealed interface Intent<T> {

  /** Register a new library; a whole-surface gate on server administration. */
  record AddLibrary() implements Intent<AuthorizationUnit> {}

  record RemoveLibrary(UUID libraryId) implements Intent<AuthorizationUnit> {}

  record ScanLibrary(UUID libraryId) implements Intent<AuthorizationUnit> {}

  record RefreshLibrary(UUID libraryId) implements Intent<AuthorizationUnit> {}

  /** See the Profiles available in the session's context Household. */
  record ViewProfilePicker() implements Intent<AuthorizationUnit> {}

  /**
   * Select a Profile in the context Household. {@code pinVerified} is trusted attempt context
   * created only by the selection service after the throttled PIN check — never by a client.
   */
  record SelectProfile(UUID profileId, boolean pinVerified) implements Intent<AuthorizationUnit> {}

  /** Read a Profile's viewing history, progress, and preferences. */
  record ViewProfileActivity(UUID profileId) implements Intent<AuthorizationUnit> {}

  record ViewHouseholdAdministration(UUID householdId) implements Intent<AuthorizationUnit> {}

  record ViewHouseholds() implements Intent<AuthorizationUnit> {}

  record ViewAccountAdministration(UUID accountId) implements Intent<AuthorizationUnit> {}

  record ViewProfileAdministration(UUID profileId) implements Intent<AuthorizationUnit> {}

  /** The live playback decision for the selected Profile in the context Household (ADR 0018). */
  record Playback() implements Intent<AuthorizationUnit> {}

  record GrantServerAdmin(UUID accountId) implements Intent<AuthorizationUnit> {}

  record RevokeServerAdmin(UUID accountId) implements Intent<AuthorizationUnit> {}

  record CreateHousehold() implements Intent<AuthorizationUnit> {}

  record RenameHousehold(UUID householdId) implements Intent<AuthorizationUnit> {}

  record RenameAccount(UUID accountId) implements Intent<AuthorizationUnit> {}

  record GrantHouseholdAdmin(UUID accountId) implements Intent<AuthorizationUnit> {}

  record RevokeHouseholdAdmin(UUID accountId) implements Intent<AuthorizationUnit> {}

  record DisableAccount(UUID accountId) implements Intent<AuthorizationUnit> {}

  record EnableAccount(UUID accountId) implements Intent<AuthorizationUnit> {}

  /** Create a Profile in a Household — its HouseholdAdmin (live, eligible) or ServerAdmin. */
  record CreateProfile(UUID householdId) implements Intent<AuthorizationUnit> {}

  /** Create a Profile and grant its named local manager — live ServerAdmin work. */
  record CreateProfileWithLocalManager(UUID householdId) implements Intent<AuthorizationUnit> {}

  /** Ordinary Profile edits: managers, supervising admins while shared in, ServerAdmin. */
  record RenameProfile(UUID profileId) implements Intent<AuthorizationUnit> {}

  record SetProfilePicture(UUID profileId) implements Intent<AuthorizationUnit> {}

  /**
   * A kind or ceiling change. The authorization module reads the current policy under the caller's
   * transaction lock, classifies the exact transition, and returns the normalized target the
   * mutation must write.
   */
  sealed interface ProfilePolicyChange extends Intent<ProfilePolicyTransition>
      permits ChangeProfileKind, SetProfileContentCeiling, ClearProfileContentCeiling {

    UUID profileId();
  }

  record ChangeProfileKind(UUID profileId, ProfileKind kind) implements ProfilePolicyChange {}

  record SetProfileContentCeiling(UUID profileId, int maximumAllowedRatingAge)
      implements ProfilePolicyChange {}

  record ClearProfileContentCeiling(UUID profileId) implements ProfilePolicyChange {}

  /** Set or remove a Profile's PIN: managers, supervising admins, ServerAdmin. */
  record ManageProfilePin(UUID profileId) implements Intent<AuthorizationUnit> {}

  /** ServerAdmin PIN reset; requires fresh reauthentication and an audit reason. */
  record AdministrativelyResetProfilePin(UUID profileId) implements Intent<AuthorizationUnit> {}

  /**
   * Ordinary standalone deletion: an unlinked, unshared Profile by its sole remaining direct
   * manager; requiresFreshReauthentication.
   */
  record DeleteProfile(UUID profileId) implements Intent<AuthorizationUnit> {}
}
