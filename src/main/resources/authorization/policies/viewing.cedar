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

// ADR 0043: a viewer sets the language preferences of the Profile it watches as, and that
// Profile's overrides of titles, while the Account is enabled and the Profile is still available
// in the context Household.
@id("set-language-preferences-of-the-selected-available-profile")
permit (
  principal,
  action == Streamarr::Action::"setLanguagePreferences",
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has contextHouseholdUsable && principal.contextHouseholdUsable &&
  principal has selectedProfile && principal.selectedProfile == resource &&
  resource has availableInContext && resource.availableInContext
};
