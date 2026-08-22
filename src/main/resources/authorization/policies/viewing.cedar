// Viewing (ADR 0024 §Core model): an Account may use a Household as a member or as a visitor
// through an active share of its Personal Profile; there it may see the picker, select any
// available, unlocked Profile (entering its PIN when one is set), and watch as the selected
// Profile while the session, the Account, the Household access, and the share all stay live.

@id("view-profile-picker-in-usable-context-household")
permit (
  principal,
  action == Streamarr::Action::"viewProfilePicker",
  resource
)
when {
  principal has contextHouseholdUsable && principal.contextHouseholdUsable &&
  principal has contextHousehold && principal.contextHousehold == resource
};

@id("select-available-unlocked-profile")
permit (
  principal,
  action == Streamarr::Action::"selectProfile",
  resource
)
when {
  principal has contextHouseholdUsable && principal.contextHouseholdUsable &&
  resource has availableInContext && resource.availableInContext &&
  resource has locked && !resource.locked &&
  resource has pinRequired && (!resource.pinRequired || context.pinVerified)
};

// ADR 0018's live playback authority: every flag is read live for this request.
@id("playback-while-session-account-household-and-share-are-live")
permit (
  principal,
  action == Streamarr::Action::"playback",
  resource
)
when {
  principal has sessionLive && principal.sessionLive &&
  principal has enabled && principal.enabled &&
  principal has contextHouseholdUsable && principal.contextHouseholdUsable &&
  principal has selectedProfile && principal.selectedProfile == resource &&
  resource has availableInContext && resource.availableInContext
};
