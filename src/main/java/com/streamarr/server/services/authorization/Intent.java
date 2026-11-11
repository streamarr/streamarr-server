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

  record StopLibraryScan(UUID libraryId) implements UnitIntent {}

  record StopLibraryRefresh(UUID libraryId) implements UnitIntent {}

  /** View library administration: each library's folder, scan status and editors. */
  record ViewLibraryAdministration() implements UnitIntent {}

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

  /**
   * The Profiles the signed-in Account manages, across Households: its own, while it is enabled,
   * never from a device-bound session.
   */
  record ViewManagedProfiles() implements UnitIntent {}

  /** Pending direct-manager invitations: ProfileManagers and live ServerAdmin only. */
  record ViewManagerInvitations(UUID profileId) implements UnitIntent {}

  /**
   * The Accounts that manage a Profile: its ProfileManagers and live ServerAdmin only, never from a
   * device-bound session.
   */
  record ViewProfileManagers(UUID profileId) implements UnitIntent {}

  /**
   * The pending manager invitations addressed to the caller's own Account: an enabled Account,
   * never from a device-bound session, which may not answer them.
   */
  record ViewReceivedManagerInvitations() implements UnitIntent {}

  /**
   * A Profile's shares into every Household and the name of each: the offering side only, meaning
   * its ProfileManagers and live ServerAdmin. The Account of a self-managed Personal Profile is one
   * of its ProfileManagers; the Account of a supervised one is not.
   */
  record ViewProfileShareHouseholds(UUID profileId) implements UnitIntent {}

  /**
   * A Household's share history, as ServerAdmin inspects it: every share into the Household at
   * every status, with its Profile, its offerer and the Household's name. Live ServerAdmin only;
   * administering the Household is not enough.
   */
  record ViewHouseholdShareHistory(UUID householdId) implements UnitIntent {}

  /**
   * Following the changes of the Profile shares the caller may read: any enabled Account, never a
   * device-bound session. Each change is then decided against the reads of its own share.
   */
  record ViewProfileShareChanges() implements UnitIntent {}

  /** The live playback decision for the selected Profile in the context Household (ADR 0018). */
  record Playback() implements UnitIntent {}

  /**
   * Set the language preferences of the Profile the session watches as, and its language overrides
   * of titles (ADR 0043).
   */
  record SetLanguagePreferences(UUID profileId) implements UnitIntent {}

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
   * or, for a self-managed Personal Profile, only its own Account or ServerAdmin, because
   * acceptance admits the person.
   */
  record OfferProfileShare(UUID profileId) implements UnitIntent {}

  /** Target HouseholdAdmin (live) or ServerAdmin decides a pending offer. */
  record AcceptProfileShare(UUID shareId) implements UnitIntent {}

  record RejectProfileShare(UUID shareId) implements UnitIntent {}

  /** The offerer or ServerAdmin withdraws a pending offer. */
  record CancelProfileShare(UUID shareId) implements UnitIntent {}

  /**
   * End an active share: a target HouseholdAdmin, a direct manager of the Profile who belongs to
   * the target Household, or the sovereign Personal Profile Account. Nobody ends a structural
   * share.
   */
  record EndProfileShare(UUID shareId) implements UnitIntent {}

  /** The ServerAdmin administrative end; requiresFreshReauthentication with a reason. */
  record AdministrativelyEndProfileShare(UUID shareId) implements UnitIntent {}

  /**
   * Issue a share code for a Household (ADR 0024 §Share codes): its live HouseholdAdmin, or
   * ServerAdmin for any Household. A code issued under this intent stays live only while its issuer
   * would still be allowed it.
   */
  record IssueShareCode(UUID householdId) implements UnitIntent {}

  /** List a Household's share codes, never the codes themselves: the seats that may issue one. */
  record ViewShareCodes(UUID householdId) implements UnitIntent {}

  /** Withdraw any of a Household's live share codes, whoever issued it: the same seats. */
  record WithdrawShareCode(UUID householdId) implements UnitIntent {}

  /**
   * Look a share code up to learn the name of the Household it names: any enabled Account, never a
   * device-bound session. The code alone decides the Household, so nothing else is read.
   */
  record ViewShareCodeHousehold() implements UnitIntent {}

  /** Propose another eligible Account as a direct ProfileManager (ADR 0024 §ProfileManager). */
  record InviteProfileManager(UUID profileId) implements UnitIntent {}

  record CancelManagerInvitation(UUID invitationId) implements UnitIntent {}

  record AcceptManagerInvitation(UUID invitationId) implements UnitIntent {}

  record DeclineManagerInvitation(UUID invitationId) implements UnitIntent {}

  /** Give up the principal's own direct manager grant. */
  record RelinquishProfileManagement(UUID profileId) implements UnitIntent {}

  /** The sovereign Account removes a direct manager of its own Personal Profile. */
  record RemoveProfileManager(UUID profileId) implements UnitIntent {}

  /** ServerAdmin grants management without recipient consent after fresh reauthentication. */
  record AdministrativelyGrantProfileManager(UUID profileId) implements UnitIntent {}

  /** ServerAdmin removes management after fresh reauthentication. */
  record AdministrativelyRemoveProfileManager(UUID profileId) implements UnitIntent {}

  /** Approve a pairing grant, binding the TV to the chosen Household (ADR 0024 §Devices). */
  record LinkDevice(UUID grantId) implements UnitIntent {}

  record RevokeDeviceRegistration(UUID registrationId) implements UnitIntent {}

  /** Refuse an ESN in one Household. */
  record BlockEsn(UUID householdId) implements UnitIntent {}

  /** Refuse an ESN server-wide: ServerAdmin, freshly reauthenticated, with an audited reason. */
  record BlockEsnServerWide() implements UnitIntent {}

  record UnblockEsn(UUID householdId) implements UnitIntent {}

  record UnblockEsnServerWide() implements UnitIntent {}

  record ViewDeviceAdministration(UUID householdId) implements UnitIntent {}

  record ViewServerDeviceAdministration() implements UnitIntent {}

  /** Move an Account — and its Personal Profile with it — to another Household (ADR 0024). */
  record TransferAccount(UUID accountId) implements UnitIntent {}

  /** ServerAdmin deletion of an Account; fresh-reauthenticated, with an audited reason. */
  record AdministrativelyDeleteAccount(UUID accountId) implements UnitIntent {}

  /** Self-deletion by an enabled Account with an unrestricted Adult Personal Profile. */
  record DeleteMyAccount() implements UnitIntent {}

  /** Move an unlinked Profile to another Household, anchoring it there first. */
  record TransferProfile(UUID profileId) implements UnitIntent {}

  /** ServerAdmin deletion of an unlinked Profile; fresh, with an audited reason. */
  record AdministrativelyDeleteProfile(UUID profileId) implements UnitIntent {}

  /** Delete a whole Household; fresh-reauthenticated ServerAdmin work with a reason. */
  record DeleteHousehold(UUID householdId) implements UnitIntent {}

  /** Only ServerAdmin reads the security audit (ADR 0024 §Audit). */
  record ViewSecurityAudit() implements UnitIntent {}

  /** Read the server's encoder preset setting (ADR 0044); a whole-surface gate. */
  record ViewEncoderPreset() implements UnitIntent {}

  /** Change the server's encoder preset setting (ADR 0044); a whole-surface gate. */
  record ChangeEncoderPreset() implements UnitIntent {}

  /** Read how long the server keeps idle stream sessions; a whole-surface gate. */
  record ViewIdleSegmentRetention() implements UnitIntent {}

  /** Change how long the server keeps idle stream sessions; a whole-surface gate. */
  record ChangeIdleSegmentRetention() implements UnitIntent {}

  /** See every active stream on the server and why it plays as it does; a whole-surface gate. */
  record ViewActiveStreams() implements UnitIntent {}

  /** Read how long the server keeps Notification history (ADR 0063); a whole-surface gate. */
  record ViewNotificationRetention() implements UnitIntent {}

  /** Change how long the server keeps Notification history (ADR 0063); a whole-surface gate. */
  record ChangeNotificationRetention() implements UnitIntent {}

  /** Read how long a share code stays live (ADR 0024 §Share codes); a whole-surface gate. */
  record ViewShareCodeLifetime() implements UnitIntent {}

  /** Change how long a share code stays live (ADR 0024 §Share codes); a whole-surface gate. */
  record ChangeShareCodeLifetime() implements UnitIntent {}

  /**
   * Read how long an invitation waits for its answer (ADR 0024 §Invitations); a whole-surface gate.
   */
  record ViewInvitationLifetime() implements UnitIntent {}

  /**
   * Change how long an invitation waits for its answer (ADR 0024 §Invitations); a whole-surface
   * gate.
   */
  record ChangeInvitationLifetime() implements UnitIntent {}

  /**
   * Read how many live share codes a Household may hold (ADR 0024 §Share codes); a whole-surface
   * gate.
   */
  record ViewShareCodeLimit() implements UnitIntent {}

  /**
   * Change how many live share codes a Household may hold (ADR 0024 §Share codes); a whole-surface
   * gate.
   */
  record ChangeShareCodeLimit() implements UnitIntent {}

  /**
   * List, count and follow the signed-in Account's own Notifications (ADR 0063): any enabled
   * Account, never a device-bound session. It reads only what was told to that Account, and nothing
   * else decides what it reads.
   */
  record ViewNotifications() implements UnitIntent {}

  /** Mark the signed-in Account's own Notifications read: the same seats as viewing them. */
  record MarkNotificationsRead() implements UnitIntent {}
}
