package com.streamarr.server.services.authorization;

import com.streamarr.server.domain.auth.ProfileKind;
import java.util.UUID;

/**
 * What a caller wants to do, in domain terms. The authorization module maps an intent to the Cedar
 * action, resource, required facts, and attempt context itself; callers never choose an action,
 * assemble entities, or supply their own reading of authority. Each result family has its own
 * sealed subtype so callers receive the allowed value without unchecked casts.
 */
public sealed interface Intent {

  /** An intent whose successful decision carries no domain value. */
  sealed interface UnitIntent extends Intent {}

  /** Register a new library; a whole-surface gate on server administration. */
  record AddLibrary() implements UnitIntent {}

  record RemoveLibrary(UUID libraryId) implements UnitIntent {}

  record ScanLibrary(UUID libraryId) implements UnitIntent {}

  record RefreshLibrary(UUID libraryId) implements UnitIntent {}

  /** See the Profiles available in the session's context Household. */
  record ViewProfilePicker() implements UnitIntent {}

  /**
   * Select a Profile in the context Household. {@code pinVerified} is trusted attempt context
   * created only by the selection service after the throttled PIN check — never by a client.
   */
  record SelectProfile(UUID profileId, boolean pinVerified) implements UnitIntent {}

  /** Read a Profile's viewing history, progress, and preferences. */
  record ViewProfileActivity(UUID profileId) implements UnitIntent {}

  record ViewHouseholdAdministration(UUID householdId) implements UnitIntent {}

  record ViewHouseholds() implements UnitIntent {}

  record ViewAccountAdministration(UUID accountId) implements UnitIntent {}

  record ViewProfileAdministration(UUID profileId) implements UnitIntent {}

  /** The live playback decision for the selected Profile in the context Household (ADR 0018). */
  record Playback() implements UnitIntent {}

  record GrantServerAdmin(UUID accountId) implements UnitIntent {}

  record RevokeServerAdmin(UUID accountId) implements UnitIntent {}

  record CreateHousehold() implements UnitIntent {}

  record RenameHousehold(UUID householdId) implements UnitIntent {}

  record RenameAccount(UUID accountId) implements UnitIntent {}

  record GrantHouseholdAdmin(UUID accountId) implements UnitIntent {}

  record RevokeHouseholdAdmin(UUID accountId) implements UnitIntent {}

  record DisableAccount(UUID accountId) implements UnitIntent {}

  record EnableAccount(UUID accountId) implements UnitIntent {}

  /** Create a Profile in a Household — its HouseholdAdmin (live, eligible) or ServerAdmin. */
  record CreateProfile(UUID householdId) implements UnitIntent {}

  /** Create a Profile and grant its named local manager — live ServerAdmin work. */
  record CreateProfileWithLocalManager(UUID householdId) implements UnitIntent {}

  /** Ordinary Profile edits: managers, supervising admins while shared in, ServerAdmin. */
  record RenameProfile(UUID profileId) implements UnitIntent {}

  record SetProfilePicture(UUID profileId) implements UnitIntent {}

  /**
   * A kind or ceiling change. The authorization module reads the current policy under the caller's
   * transaction lock, classifies the exact transition, and returns the normalized target the
   * mutation must write.
   */
  sealed interface ProfilePolicyChange extends Intent
      permits ChangeProfileKind, SetProfileContentCeiling, ClearProfileContentCeiling {

    UUID profileId();
  }

  record ChangeProfileKind(UUID profileId, ProfileKind kind) implements ProfilePolicyChange {}

  record SetProfileContentCeiling(UUID profileId, int maximumAllowedRatingAge)
      implements ProfilePolicyChange {}

  record ClearProfileContentCeiling(UUID profileId) implements ProfilePolicyChange {}

  /** Set or remove a Profile's PIN: managers and supervising admins. */
  record ManageProfilePin(UUID profileId) implements UnitIntent {}

  /** ServerAdmin PIN reset; requires fresh reauthentication and an audit reason. */
  record AdministrativelyResetProfilePin(UUID profileId) implements UnitIntent {}

  /**
   * Ordinary standalone deletion: an unlinked, unshared Profile by its sole remaining direct
   * manager; requiresFreshReauthentication.
   */
  record DeleteProfile(UUID profileId) implements UnitIntent {}

  /** Only ServerAdmin proposes new Accounts (ADR 0024 §Invitations); a whole-surface gate. */
  record IssueAccountInvitation() implements UnitIntent {}

  record CancelAccountInvitation() implements UnitIntent {}

  /** Account invitations are visible to ServerAdmin (plus the code holder, outside Cedar). */
  record ViewAccountInvitations() implements UnitIntent {}

  /** Issue a password-reset code; requiresFreshReauthentication with a reason (ADR 0024). */
  record IssuePasswordReset(UUID accountId) implements UnitIntent {}

  /**
   * Offer one Profile to a Household (ADR 0024 §Profile sharing): ServerAdmin, a direct manager —
   * or, for a self-managed Personal Profile, only its own Account, because acceptance admits the
   * person.
   */
  record OfferProfileShare(UUID profileId) implements UnitIntent {}

  /** Target HouseholdAdmin (live) or ServerAdmin decides a pending offer. */
  record AcceptProfileShare(UUID shareId) implements UnitIntent {}

  record RejectProfileShare(UUID shareId) implements UnitIntent {}

  /** The offerer or ServerAdmin withdraws a pending offer. */
  record CancelProfileShare(UUID shareId) implements UnitIntent {}

  /**
   * End an active share: a target HouseholdAdmin, a direct manager of the Profile who belongs to
   * the target Household, the sovereign Personal Profile Account, or ServerAdmin. Nobody ends a
   * structural share.
   */
  record EndProfileShare(UUID shareId) implements UnitIntent {}

  /** ServerAdmin force-end; requiresFreshReauthentication with a reason. */
  record ForceEndProfileShare(UUID shareId) implements UnitIntent {}
}
