// Profile management (ADR 0024 §ProfileManager, §Restricted Profile supervision, §Profile
// creation, §Permanent Profile deletion). Management is portable authority; supervision is
// share-scoped and never survives the share; sovereignty and eligibility are live facts.

// A HouseholdAdmin creates Profiles in their own Household while eligible; ServerAdmin anywhere.
@id("create-profile-as-eligible-local-admin-or-server-admin")
permit (
  principal,
  action == Streamarr::Action::"createProfile",
  resource
)
when {
  principal has enabled && principal.enabled &&
  ((principal has liveHouseholdRole && principal.liveHouseholdRole == "ADMIN" &&
    principal has liveMembershipHousehold && principal.liveMembershipHousehold == resource &&
    principal has eligible && principal.eligible) ||
   (principal has serverAdmin && principal.serverAdmin))
};

// Only ServerAdmin may establish a portable manager relationship during creation. An ordinary
// HouseholdAdmin becomes manager by creating the Profile but cannot name another Account.
@id("create-profile-with-local-manager-as-server-admin")
permit (
  principal,
  action == Streamarr::Action::"createProfileWithLocalManager",
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin
};

// Ordinary edits: managers, supervising admins while the restricted Profile is shared in,
// ServerAdmin.
@id("edit-profile-as-manager-supervisor-or-server-admin")
permit (
  principal,
  action == Streamarr::Action::"editProfile",
  resource
)
when {
  principal has enabled && principal.enabled &&
  ((resource has managedByPrincipal && resource.managedByPrincipal) ||
   (resource has supervisedByPrincipal && resource.supervisedByPrincipal) ||
   (principal has serverAdmin && principal.serverAdmin))
};

// Ordinary PIN management: managers or supervising admins while the restricted Profile is shared
// in. ServerAdmin uses the separately authenticated and audited administrative reset.
@id("manage-profile-pin-as-manager-or-supervisor")
permit (
  principal,
  action == Streamarr::Action::"manageProfilePin",
  resource
)
when {
  principal has enabled && principal.enabled &&
  ((resource has managedByPrincipal && resource.managedByPrincipal) ||
   (resource has supervisedByPrincipal && resource.supervisedByPrincipal))
};

// Kind is a manager decision — supervision never changes what a Profile is.
@id("change-profile-kind-as-manager-or-server-admin")
permit (
  principal,
  action == Streamarr::Action::"changeProfileKind",
  resource
)
when {
  principal has enabled && principal.enabled &&
  ((resource has managedByPrincipal && resource.managedByPrincipal) ||
   (principal has serverAdmin && principal.serverAdmin))
};

// Leaving a Profile unrestricted ends supervision — whoever does it re-enters their password.
@id("lift-final-restriction-as-fresh-manager-or-server-admin")
permit (
  principal,
  action == Streamarr::Action::"liftFinalRestriction",
  resource
)
when {
  principal has enabled && principal.enabled &&
  context.freshReauthentication &&
  ((resource has managedByPrincipal && resource.managedByPrincipal) ||
   (principal has serverAdmin && principal.serverAdmin))
};

// Restricting a sovereign Adult's own Personal Profile is a ServerAdmin override.
@id("restrict-sovereign-adult-as-fresh-server-admin")
permit (
  principal,
  action == Streamarr::Action::"restrictSovereignAdult",
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin &&
  context.freshReauthentication
};

// Administrative PIN reset: live ServerAdmin, fresh reauthentication; the reason is audited.
@id("administratively-reset-profile-pin-as-fresh-server-admin")
permit (
  principal,
  action == Streamarr::Action::"administrativelyResetProfilePin",
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin &&
  context.freshReauthentication
};

// Ordinary standalone deletion: the sole remaining direct manager of an unlinked, unshared
// Profile, freshly reauthenticated. ServerAdmin has no shortcut here — force-delete is its own
// audited override and arrives with transfers and deletion.
@id("delete-profile-as-fresh-sole-manager")
permit (
  principal,
  action == Streamarr::Action::"deleteProfile",
  resource
)
when {
  principal has enabled && principal.enabled &&
  context.freshReauthentication &&
  resource has unlinked && resource.unlinked &&
  resource has shareFree && resource.shareFree &&
  resource has principalSoleManager && resource.principalSoleManager
};
