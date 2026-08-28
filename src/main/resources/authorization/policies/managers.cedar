// Direct ProfileManagers (ADR 0024 §ProfileManager): durable, portable authority granted by
// invitation and consent, or by a ServerAdmin override. Share-derived supervision cannot create a
// portable relationship. Lifecycle (pending, expiry) is the conditional transition's business,
// not authority's.

// A direct or self-derived ProfileManager proposes; the recipient must still consent.
@id("invite-profile-manager-as-manager")
permit (
  principal,
  action == Streamarr::Action::"inviteProfileManager",
  resource
)
when {
  principal has enabled && principal.enabled &&
  resource has managedByPrincipal && resource.managedByPrincipal
};

// Pending invitations expose their named recipient, so share-derived administration is too broad.
@id("view-manager-invitations-as-manager-or-server-admin")
permit (
  principal,
  action == Streamarr::Action::"viewManagerInvitations",
  resource
)
when {
  principal has enabled && principal.enabled &&
  ((resource has managedByPrincipal && resource.managedByPrincipal) ||
   (principal has serverAdmin && principal.serverAdmin))
};

// Only the named recipient answers an invitation addressed to them.
@id("decide-manager-invitation-as-named-recipient")
permit (
  principal,
  action in
    [Streamarr::Action::"acceptManagerInvitation",
     Streamarr::Action::"declineManagerInvitation"],
  resource
)
when {
  principal has enabled && principal.enabled &&
  resource has recipientIsPrincipal && resource.recipientIsPrincipal
};

// The inviter may withdraw while pending; ServerAdmin may always.
@id("cancel-manager-invitation-as-inviter-or-server-admin")
permit (
  principal,
  action == Streamarr::Action::"cancelManagerInvitation",
  resource
)
when {
  principal has enabled && principal.enabled &&
  ((resource has invitedByPrincipal && resource.invitedByPrincipal) ||
   (principal has serverAdmin && principal.serverAdmin))
};

// A direct manager gives up only their own stored grant; self-management has no row to give up.
@id("relinquish-profile-management-as-direct-manager")
permit (
  principal,
  action == Streamarr::Action::"relinquishProfileManagement",
  resource
)
when {
  principal has enabled && principal.enabled &&
  resource has directlyManagedByPrincipal && resource.directlyManagedByPrincipal
};

// The sovereign Account curates its own Personal Profile's managers; peers cannot remove peers.
@id("remove-profile-manager-as-sovereign-account")
permit (
  principal,
  action == Streamarr::Action::"removeProfileManager",
  resource
)
when {
  principal has enabled && principal.enabled &&
  resource has sovereignPersonalProfileOfPrincipal &&
  resource.sovereignPersonalProfileOfPrincipal
};

// Administrative GRANT: live ServerAdmin, fresh reauthentication; the reason is audited.
@id("administratively-grant-profile-manager-as-fresh-server-admin")
permit (
  principal,
  action == Streamarr::Action::"administrativelyGrantProfileManager",
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin &&
  context.freshReauthentication
};

// Administrative REMOVE: live ServerAdmin, fresh reauthentication; the reason is audited.
@id("administratively-remove-profile-manager-as-fresh-server-admin")
permit (
  principal,
  action == Streamarr::Action::"administrativelyRemoveProfileManager",
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin &&
  context.freshReauthentication
};
