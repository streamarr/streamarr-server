// Administration writes (ADR 0024 §ServerAdmin): ServerAdmin authority changes are members of the
// requiresFreshReauthentication group — a stolen ServerAdmin session must not be enough. Both
// authority facts are live; the freshness flag is trusted context stamped by the decider, never
// client input.

@id("server-admin-authority-changes-require-live-admin-and-fresh-reauthentication")
permit (
  principal,
  action in
    [Streamarr::Action::"grantServerAdmin",
     Streamarr::Action::"revokeServerAdmin"],
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin &&
  context.freshReauthentication
};

// Household role changes, enable/disable, and Household creation require live ServerAdmin
// authority, but not fresh reauthentication.
@id("account-administration-writes-require-live-enabled-server-admin")
permit (
  principal,
  action in
    [Streamarr::Action::"grantHouseholdAdmin",
     Streamarr::Action::"revokeHouseholdAdmin",
     Streamarr::Action::"disableAccount",
     Streamarr::Action::"enableAccount",
     Streamarr::Action::"createHousehold"],
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin
};

// A Household's settings belong to its HouseholdAdmins (live role — a role change takes effect on
// the next decision, not the next login) and to ServerAdmin.
@id("rename-household-as-live-household-admin-or-server-admin")
permit (
  principal,
  action == Streamarr::Action::"renameHousehold",
  resource
)
when {
  principal has enabled && principal.enabled &&
  ((principal has liveHouseholdRole && principal.liveHouseholdRole == "ADMIN" &&
    principal has liveMembershipHousehold && principal.liveMembershipHousehold == resource) ||
   (principal has serverAdmin && principal.serverAdmin))
};

// An Account edits its own administrative display name; ServerAdmin edits any.
@id("rename-account-as-self-or-server-admin")
permit (
  principal,
  action == Streamarr::Action::"renameAccount",
  resource
)
when {
  principal has enabled && principal.enabled &&
  (principal == resource ||
   (principal has serverAdmin && principal.serverAdmin))
};

// Only ServerAdmin proposes, replaces, cancels, or inspects Account invitations
// (ADR 0024 §Invitations); the recipient's own ceremony authenticates by code, outside Cedar.
@id("account-invitations-are-server-admin-work")
permit (
  principal,
  action in Streamarr::Action::"invitationAdministration",
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin
};

// Issuing a password-reset code is a break-glass member of the fresh-reauthentication group.
@id("issue-password-reset-as-fresh-server-admin")
permit (
  principal,
  action == Streamarr::Action::"issuePasswordReset",
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin &&
  context.freshReauthentication
};
