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

  /** The server-wide Household catalogue; ServerAdmin reads all application-domain data. */
  record ViewHouseholds() implements Intent<AuthorizationUnit> {}

  record ViewAccountAdministration(UUID accountId) implements Intent<AuthorizationUnit> {}

  record ViewProfileAdministration(UUID profileId) implements Intent<AuthorizationUnit> {}

  /** The live playback decision for the selected Profile in the context Household (ADR 0018). */
  record Playback() implements Intent<AuthorizationUnit> {}

  /** Grant server-wide authority; requiresFreshReauthentication (ADR 0024 §ServerAdmin). */
  record GrantServerAdmin(UUID accountId) implements Intent<AuthorizationUnit> {}

  /** Revoke server-wide authority; requiresFreshReauthentication (ADR 0024 §ServerAdmin). */
  record RevokeServerAdmin(UUID accountId) implements Intent<AuthorizationUnit> {}

  /** Create an empty Household; live ServerAdmin work. */
  record CreateHousehold() implements Intent<AuthorizationUnit> {}

  /** Edit a Household's settings — that Household's HouseholdAdmin (live role) or ServerAdmin. */
  record RenameHousehold(UUID householdId) implements Intent<AuthorizationUnit> {}

  /** Edit an Account's administrative display name — that Account itself or ServerAdmin. */
  record RenameAccount(UUID accountId) implements Intent<AuthorizationUnit> {}

  /** Household role changes are ServerAdmin work (ADR 0024 §ServerAdmin). */
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

  /** Set or clear a Profile's PIN: managers, supervising admins, ServerAdmin. */
  record ManageProfilePin(UUID profileId) implements Intent<AuthorizationUnit> {}

  /** ServerAdmin PIN break-glass; requiresFreshReauthentication with a reason. */
  record OverrideProfilePin(UUID profileId) implements Intent<AuthorizationUnit> {}

  /**
   * Ordinary standalone deletion: an unlinked, unshared Profile by its sole remaining direct
   * manager; requiresFreshReauthentication.
   */
  record DeleteProfile(UUID profileId) implements Intent<AuthorizationUnit> {}

  /** Only ServerAdmin proposes new Accounts (ADR 0024 §Invitations); a whole-surface gate. */
  record IssueAccountInvitation() implements Intent<AuthorizationUnit> {}

  record CancelAccountInvitation() implements Intent<AuthorizationUnit> {}

  /** Account invitations are visible to ServerAdmin (plus the code holder, outside Cedar). */
  record ViewAccountInvitations() implements Intent<AuthorizationUnit> {}

  /** Issue a password-reset code; requiresFreshReauthentication with a reason (ADR 0024). */
  record IssuePasswordReset(UUID accountId) implements Intent<AuthorizationUnit> {}

  /**
   * Offer one Profile to a Household (ADR 0024 §Profile sharing): ServerAdmin, a direct manager —
   * or, for a self-managed Personal Profile, only its own Account, because acceptance admits the
   * person.
   */
  record OfferProfileShare(UUID profileId) implements Intent<AuthorizationUnit> {}

  /** Target HouseholdAdmin (live) or ServerAdmin decides a pending offer. */
  record AcceptProfileShare(UUID shareId) implements Intent<AuthorizationUnit> {}

  record RejectProfileShare(UUID shareId) implements Intent<AuthorizationUnit> {}

  /** The offerer or ServerAdmin withdraws a pending offer. */
  record CancelProfileShare(UUID shareId) implements Intent<AuthorizationUnit> {}

  /**
   * End an active share: a target HouseholdAdmin, a direct manager of the Profile who belongs to
   * the target Household, the sovereign Personal Profile Account, or ServerAdmin. Nobody ends a
   * structural share.
   */
  record EndProfileShare(UUID shareId) implements Intent<AuthorizationUnit> {}

  /** ServerAdmin force-end; requiresFreshReauthentication with a reason. */
  record ForceEndProfileShare(UUID shareId) implements Intent<AuthorizationUnit> {}

  /** Propose another eligible Account as a direct ProfileManager (ADR 0024 §ProfileManager). */
  record InviteProfileManager(UUID profileId) implements Intent<AuthorizationUnit> {}

  record CancelManagerInvitation(UUID invitationId) implements Intent<AuthorizationUnit> {}

  record AcceptManagerInvitation(UUID invitationId) implements Intent<AuthorizationUnit> {}

  record DeclineManagerInvitation(UUID invitationId) implements Intent<AuthorizationUnit> {}

  /** Give up the principal's own direct manager grant. */
  record RelinquishProfileManagement(UUID profileId) implements Intent<AuthorizationUnit> {}

  /** The sovereign Account removes a direct manager of its own Personal Profile. */
  record RemoveProfileManager(UUID profileId) implements Intent<AuthorizationUnit> {}

  /** ServerAdmin grants or removes management as a fresh-reauthenticated, audited override. */
  record OverrideProfileManager(UUID profileId) implements Intent<AuthorizationUnit> {}
}
