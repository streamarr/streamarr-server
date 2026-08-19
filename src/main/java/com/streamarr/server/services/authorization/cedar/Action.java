package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.EntityUID;
import java.util.EnumSet;
import java.util.Set;

/** The Cedar actions Streamarr evaluates; names match {@code streamarr.cedarschema} exactly. */
enum Action {
  ADD_LIBRARY("addLibrary", ResourceKind.SERVER, FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  REMOVE_LIBRARY("removeLibrary", ResourceKind.SERVER, FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  SCAN_LIBRARY("scanLibrary", ResourceKind.SERVER, FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  REFRESH_LIBRARY("refreshLibrary", ResourceKind.SERVER, FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  VIEW_PROFILE_PICKER(
      "viewProfilePicker",
      ResourceKind.HOUSEHOLD,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.CONTEXT_HOUSEHOLD_ACCESS),
  SELECT_PROFILE(
      "selectProfile",
      ResourceKind.PROFILE,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.CONTEXT_HOUSEHOLD_ACCESS,
      FactRequirement.PROFILE_AVAILABILITY),
  PLAYBACK(
      "playback",
      ResourceKind.PROFILE,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.CONTEXT_HOUSEHOLD_ACCESS,
      FactRequirement.SESSION_LIVENESS,
      FactRequirement.PROFILE_AVAILABILITY),
  VIEW_PROFILE_ACTIVITY(
      "viewProfileActivity",
      ResourceKind.PROFILE,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.PROFILE_MANAGEMENT),
  VIEW_HOUSEHOLD_ADMINISTRATION(
      "viewHouseholdAdministration",
      ResourceKind.HOUSEHOLD,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  VIEW_ACCOUNT_ADMINISTRATION(
      "viewAccountAdministration",
      ResourceKind.ACCOUNT,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.ACCOUNT_HOUSEHOLD),
  VIEW_PROFILE_ADMINISTRATION(
      "viewProfileAdministration",
      ResourceKind.PROFILE,
      FactRequirement.SIGNED_PRINCIPAL_CONTEXT,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.PROFILE_MANAGEMENT),
  GRANT_SERVER_ADMIN(
      "grantServerAdmin",
      ResourceKind.ACCOUNT,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  REVOKE_SERVER_ADMIN(
      "revokeServerAdmin",
      ResourceKind.ACCOUNT,
      FreshReauthentication.REQUIRED,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  CREATE_HOUSEHOLD(
      "createHousehold", ResourceKind.SERVER, FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  RENAME_HOUSEHOLD(
      "renameHousehold",
      ResourceKind.HOUSEHOLD,
      FactRequirement.LIVE_PRINCIPAL_AUTHORITY,
      FactRequirement.LIVE_PRINCIPAL_HOUSEHOLD),
  RENAME_ACCOUNT("renameAccount", ResourceKind.ACCOUNT, FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  GRANT_HOUSEHOLD_ADMIN(
      "grantHouseholdAdmin", ResourceKind.ACCOUNT, FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  REVOKE_HOUSEHOLD_ADMIN(
      "revokeHouseholdAdmin", ResourceKind.ACCOUNT, FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  DISABLE_ACCOUNT("disableAccount", ResourceKind.ACCOUNT, FactRequirement.LIVE_PRINCIPAL_AUTHORITY),
  ENABLE_ACCOUNT("enableAccount", ResourceKind.ACCOUNT, FactRequirement.LIVE_PRINCIPAL_AUTHORITY);

  private static final String ACTION_TYPE = "Streamarr::Action";

  private final String cedarName;
  private final ResourceKind resourceKind;
  private final FreshReauthentication freshReauthentication;
  private final Set<FactRequirement> facts;

  Action(
      String cedarName, ResourceKind resourceKind, FactRequirement first, FactRequirement... rest) {
    this(cedarName, resourceKind, FreshReauthentication.NOT_REQUIRED, first, rest);
  }

  Action(
      String cedarName,
      ResourceKind resourceKind,
      FreshReauthentication freshReauthentication,
      FactRequirement first,
      FactRequirement... rest) {
    this.cedarName = cedarName;
    this.resourceKind = resourceKind;
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

  /** Membership in ADR 0024's requiresFreshReauthentication action group. */
  boolean requiresFreshReauthentication() {
    return freshReauthentication == FreshReauthentication.REQUIRED;
  }

  enum FreshReauthentication {
    REQUIRED,
    NOT_REQUIRED
  }

  enum ResourceKind {
    SERVER,
    HOUSEHOLD,
    ACCOUNT,
    PROFILE
  }
}
