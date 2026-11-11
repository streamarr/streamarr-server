package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.EntityUID;
import java.util.EnumSet;
import java.util.Set;

/** The Cedar actions Streamarr evaluates; names match {@code streamarr.cedarschema} exactly. */
enum Action {
  ADD_LIBRARY(
      "addLibrary",
      ResourceKind.SERVER,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  REMOVE_LIBRARY(
      "removeLibrary",
      ResourceKind.SERVER,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  SCAN_LIBRARY(
      "scanLibrary",
      ResourceKind.SERVER,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  REFRESH_LIBRARY(
      "refreshLibrary",
      ResourceKind.SERVER,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  STOP_LIBRARY_SCAN(
      "stopLibraryScan",
      ResourceKind.SERVER,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  STOP_LIBRARY_REFRESH(
      "stopLibraryRefresh",
      ResourceKind.SERVER,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  VIEW_LIBRARY_ADMINISTRATION(
      "viewLibraryAdministration",
      ResourceKind.SERVER,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  VIEW_PROFILE_PICKER(
      "viewProfilePicker",
      ResourceKind.HOUSEHOLD,
      Authorizes.READ,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.CONTEXT_HOUSEHOLD_ACCESS),
  SELECT_PROFILE(
      "selectProfile",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.CONTEXT_HOUSEHOLD_ACCESS,
      FactRequirement.PROFILE_AVAILABILITY),
  PLAYBACK(
      "playback",
      ResourceKind.PROFILE,
      Authorizes.READ,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.CONTEXT_HOUSEHOLD_ACCESS,
      FactRequirement.SESSION_LIVENESS,
      FactRequirement.PROFILE_AVAILABILITY),
  SET_LANGUAGE_PREFERENCES(
      "setLanguagePreferences",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.CONTEXT_HOUSEHOLD_ACCESS,
      FactRequirement.PROFILE_AVAILABILITY),
  VIEW_PROFILE_ACTIVITY(
      "viewProfileActivity",
      ResourceKind.PROFILE,
      Authorizes.READ,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.PROFILE_MANAGEMENT),
  VIEW_HOUSEHOLD_ADMINISTRATION(
      "viewHouseholdAdministration",
      ResourceKind.HOUSEHOLD,
      Authorizes.READ,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  VIEW_ACCOUNT_ADMINISTRATION(
      "viewAccountAdministration",
      ResourceKind.ACCOUNT,
      Authorizes.READ,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.ACCOUNT_HOUSEHOLD),
  VIEW_PROFILE_ADMINISTRATION(
      "viewProfileAdministration",
      ResourceKind.PROFILE,
      Authorizes.READ,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.PROFILE_MANAGEMENT),
  VIEW_MANAGER_INVITATIONS(
      "viewManagerInvitations",
      ResourceKind.PROFILE,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.PROFILE_MANAGEMENT),
  VIEW_PROFILE_SHARE_HOUSEHOLDS(
      "viewProfileShareHouseholds",
      ResourceKind.PROFILE,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.PROFILE_MANAGEMENT),
  VIEW_HOUSEHOLD_SHARE_HISTORY(
      "viewHouseholdShareHistory",
      ResourceKind.HOUSEHOLD,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  VIEW_PROFILE_SHARE_CHANGES(
      "viewProfileShareChanges",
      ResourceKind.SERVER,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  GRANT_SERVER_ADMIN(
      "grantServerAdmin",
      ResourceKind.ACCOUNT,
      Authorizes.WRITE,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  REVOKE_SERVER_ADMIN(
      "revokeServerAdmin",
      ResourceKind.ACCOUNT,
      Authorizes.WRITE,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  CREATE_HOUSEHOLD(
      "createHousehold",
      ResourceKind.SERVER,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  VIEW_HOUSEHOLDS(
      "viewHouseholds",
      ResourceKind.SERVER,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  RENAME_HOUSEHOLD(
      "renameHousehold",
      ResourceKind.HOUSEHOLD,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.LIVE_PRINCIPAL_HOUSEHOLD),
  RENAME_ACCOUNT(
      "renameAccount",
      ResourceKind.ACCOUNT,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  GRANT_HOUSEHOLD_ADMIN(
      "grantHouseholdAdmin",
      ResourceKind.ACCOUNT,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  REVOKE_HOUSEHOLD_ADMIN(
      "revokeHouseholdAdmin",
      ResourceKind.ACCOUNT,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  DISABLE_ACCOUNT(
      "disableAccount",
      ResourceKind.ACCOUNT,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  ENABLE_ACCOUNT(
      "enableAccount",
      ResourceKind.ACCOUNT,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  CREATE_PROFILE(
      "createProfile",
      ResourceKind.HOUSEHOLD,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.LIVE_PRINCIPAL_HOUSEHOLD,
      FactRequirement.PRINCIPAL_ELIGIBILITY),
  CREATE_PROFILE_WITH_LOCAL_MANAGER(
      "createProfileWithLocalManager",
      ResourceKind.HOUSEHOLD,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  EDIT_PROFILE(
      "editProfile",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.PROFILE_MANAGEMENT,
      FactRequirement.PROFILE_SUPERVISION),
  CHANGE_PROFILE_KIND(
      "changeProfileKind",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.PROFILE_MANAGEMENT),
  LIFT_FINAL_RESTRICTION(
      "liftFinalRestriction",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.PROFILE_MANAGEMENT),
  RESTRICT_SOVEREIGN_ADULT(
      "restrictSovereignAdult",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  MANAGE_PROFILE_PIN(
      "manageProfilePin",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.PROFILE_MANAGEMENT,
      FactRequirement.PROFILE_SUPERVISION),
  ADMINISTRATIVELY_RESET_PROFILE_PIN(
      "administrativelyResetProfilePin",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  DELETE_PROFILE(
      "deleteProfile",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.PROFILE_DELETION),
  ISSUE_ACCOUNT_INVITATION(
      "issueAccountInvitation",
      ResourceKind.SERVER,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  CANCEL_ACCOUNT_INVITATION(
      "cancelAccountInvitation",
      ResourceKind.SERVER,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  VIEW_ACCOUNT_INVITATIONS(
      "viewAccountInvitations",
      ResourceKind.SERVER,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  ISSUE_PASSWORD_RESET(
      "issuePasswordReset",
      ResourceKind.ACCOUNT,
      Authorizes.WRITE,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  OFFER_PROFILE_SHARE(
      "offerProfileShare",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.PROFILE_MANAGEMENT),
  ACCEPT_PROFILE_SHARE(
      "acceptProfileShare",
      ResourceKind.SHARE,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.SHARE_FACTS),
  REJECT_PROFILE_SHARE(
      "rejectProfileShare",
      ResourceKind.SHARE,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.SHARE_FACTS),
  CANCEL_PROFILE_SHARE(
      "cancelProfileShare",
      ResourceKind.SHARE,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.SHARE_FACTS),
  END_PROFILE_SHARE(
      "endProfileShare",
      ResourceKind.SHARE,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.SHARE_FACTS),
  ADMINISTRATIVELY_END_PROFILE_SHARE(
      "administrativelyEndProfileShare",
      ResourceKind.SHARE,
      Authorizes.WRITE,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.SHARE_FACTS),
  ISSUE_SHARE_CODE(
      "issueShareCode",
      ResourceKind.HOUSEHOLD,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.LIVE_PRINCIPAL_HOUSEHOLD),
  VIEW_SHARE_CODES(
      "viewShareCodes",
      ResourceKind.HOUSEHOLD,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.LIVE_PRINCIPAL_HOUSEHOLD),
  WITHDRAW_SHARE_CODE(
      "withdrawShareCode",
      ResourceKind.HOUSEHOLD,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.LIVE_PRINCIPAL_HOUSEHOLD),
  VIEW_SHARE_CODE_HOUSEHOLD(
      "viewShareCodeHousehold",
      ResourceKind.SERVER,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  INVITE_PROFILE_MANAGER(
      "inviteProfileManager",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.PROFILE_MANAGEMENT),
  CANCEL_MANAGER_INVITATION(
      "cancelManagerInvitation",
      ResourceKind.MANAGER_INVITATION,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.MANAGER_INVITATION_FACTS),
  ACCEPT_MANAGER_INVITATION(
      "acceptManagerInvitation",
      ResourceKind.MANAGER_INVITATION,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.MANAGER_INVITATION_FACTS),
  DECLINE_MANAGER_INVITATION(
      "declineManagerInvitation",
      ResourceKind.MANAGER_INVITATION,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.MANAGER_INVITATION_FACTS),
  RELINQUISH_PROFILE_MANAGEMENT(
      "relinquishProfileManagement",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.PROFILE_MANAGEMENT),
  REMOVE_PROFILE_MANAGER(
      "removeProfileManager",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.PROFILE_MANAGEMENT),
  ADMINISTRATIVELY_GRANT_PROFILE_MANAGER(
      "administrativelyGrantProfileManager",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  ADMINISTRATIVELY_REMOVE_PROFILE_MANAGER(
      "administrativelyRemoveProfileManager",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  LINK_DEVICE(
      "linkDevice",
      ResourceKind.GRANT,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT),
  REVOKE_DEVICE_REGISTRATION(
      "revokeDeviceRegistration",
      ResourceKind.REGISTRATION,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.REGISTRATION_FACTS),
  BLOCK_ESN(
      "blockEsn",
      ResourceKind.HOUSEHOLD,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.LIVE_PRINCIPAL_HOUSEHOLD),
  BLOCK_ESN_SERVER_WIDE(
      "blockEsnServerWide",
      ResourceKind.SERVER,
      Authorizes.WRITE,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT),
  UNBLOCK_ESN(
      "unblockEsn",
      ResourceKind.HOUSEHOLD,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.LIVE_PRINCIPAL_HOUSEHOLD),
  UNBLOCK_ESN_SERVER_WIDE(
      "unblockEsnServerWide",
      ResourceKind.SERVER,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT),
  VIEW_DEVICE_ADMINISTRATION(
      "viewDeviceAdministration",
      ResourceKind.HOUSEHOLD,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.LIVE_PRINCIPAL_HOUSEHOLD),
  VIEW_SERVER_DEVICE_ADMINISTRATION(
      "viewServerDeviceAdministration",
      ResourceKind.SERVER,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT),
  TRANSFER_ACCOUNT(
      "transferAccount",
      ResourceKind.ACCOUNT,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  ADMINISTRATIVELY_DELETE_ACCOUNT(
      "administrativelyDeleteAccount",
      ResourceKind.ACCOUNT,
      Authorizes.WRITE,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  DELETE_MY_ACCOUNT(
      "deleteMyAccount",
      ResourceKind.ACCOUNT,
      Authorizes.WRITE,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.PRINCIPAL_ELIGIBILITY),
  TRANSFER_PROFILE(
      "transferProfile",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  ADMINISTRATIVELY_DELETE_PROFILE(
      "administrativelyDeleteProfile",
      ResourceKind.PROFILE,
      Authorizes.WRITE,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  DELETE_HOUSEHOLD(
      "deleteHousehold",
      ResourceKind.HOUSEHOLD,
      Authorizes.WRITE,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  VIEW_SECURITY_AUDIT(
      "viewSecurityAudit",
      ResourceKind.SERVER,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  VIEW_ENCODER_PRESET(
      "viewEncoderPreset",
      ResourceKind.SERVER,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  CHANGE_ENCODER_PRESET(
      "changeEncoderPreset",
      ResourceKind.SERVER,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  VIEW_IDLE_SEGMENT_RETENTION(
      "viewIdleSegmentRetention",
      ResourceKind.SERVER,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  CHANGE_IDLE_SEGMENT_RETENTION(
      "changeIdleSegmentRetention",
      ResourceKind.SERVER,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  VIEW_ACTIVE_STREAMS(
      "viewActiveStreams",
      ResourceKind.SERVER,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  VIEW_NOTIFICATION_RETENTION(
      "viewNotificationRetention",
      ResourceKind.SERVER,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  CHANGE_NOTIFICATION_RETENTION(
      "changeNotificationRetention",
      ResourceKind.SERVER,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  VIEW_SHARE_CODE_LIFETIME(
      "viewShareCodeLifetime",
      ResourceKind.SERVER,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  CHANGE_SHARE_CODE_LIFETIME(
      "changeShareCodeLifetime",
      ResourceKind.SERVER,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  VIEW_NOTIFICATIONS(
      "viewNotifications",
      ResourceKind.ACCOUNT,
      Authorizes.READ,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  MARK_NOTIFICATIONS_READ(
      "markNotificationsRead",
      ResourceKind.ACCOUNT,
      Authorizes.WRITE,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY);

  private static final String ACTION_TYPE = "Streamarr::Action";

  private final String cedarName;
  private final ResourceKind resourceKind;
  private final Authorizes authorizes;
  private final FreshReauthentication freshReauthentication;
  private final Set<FactRequirement> facts;

  Action(
      String cedarName,
      ResourceKind resourceKind,
      Authorizes authorizes,
      FactRequirement first,
      FactRequirement... rest) {
    this(cedarName, resourceKind, authorizes, FreshReauthentication.NOT_REQUIRED, first, rest);
  }

  Action(
      String cedarName,
      ResourceKind resourceKind,
      Authorizes authorizes,
      FreshReauthentication freshReauthentication,
      FactRequirement first,
      FactRequirement... rest) {
    this.cedarName = cedarName;
    this.resourceKind = resourceKind;
    this.authorizes = authorizes;
    this.freshReauthentication = freshReauthentication;
    this.facts = Set.copyOf(EnumSet.of(first, rest));
  }

  String cedarName() {
    return cedarName;
  }

  ResourceKind resourceKind() {
    return resourceKind;
  }

  EntityUID uid() {
    return CedarIds.uid(ACTION_TYPE, cedarName);
  }

  /** Facts the slice must carry for this action. */
  Set<FactRequirement> facts() {
    return facts;
  }

  /**
   * Whether an allowed decision lets the caller write. Contributors read a write's relationship
   * facts under shared row locks, so a decision made inside the writing transaction holds them
   * until that transaction ends (ADR 0025). A read takes no lock, so a read-only transaction can
   * decide it.
   */
  boolean authorizesWrite() {
    return authorizes == Authorizes.WRITE;
  }

  boolean requiresFreshReauthentication() {
    return freshReauthentication == FreshReauthentication.REQUIRED;
  }

  enum Authorizes {
    READ,
    WRITE
  }

  enum FreshReauthentication {
    REQUIRED,
    NOT_REQUIRED
  }

  enum ResourceKind {
    SERVER,
    HOUSEHOLD,
    ACCOUNT,
    PROFILE,
    SHARE,
    MANAGER_INVITATION,
    GRANT,
    REGISTRATION
  }
}
