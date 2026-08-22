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
      FactRequirement.PROFILE_MANAGEMENT);

  private static final String ACTION_TYPE = "Streamarr::Action";

  private final String cedarName;
  private final ResourceKind resourceKind;
  private final Set<FactRequirement> facts;

  Action(
      String cedarName, ResourceKind resourceKind, FactRequirement first, FactRequirement... rest) {
    this.cedarName = cedarName;
    this.resourceKind = resourceKind;
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

  enum ResourceKind {
    SERVER,
    HOUSEHOLD,
    ACCOUNT,
    PROFILE
  }
}
