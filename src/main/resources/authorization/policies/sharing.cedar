// Profile sharing (ADR 0024 §Profile sharing): a share makes one Profile available to a Household
// without copying data. Share-derived authority never creates another portable relationship.

// ServerAdmin or a manager offers; a self-managed Personal Profile is offered only by its own
// Account, because acceptance admits the person to the Household.
@id("offer-profile-share-as-offerable-manager-or-server-admin")
permit (
  principal,
  action == Streamarr::Action::"offerProfileShare",
  resource
)
when {
  principal has enabled && principal.enabled &&
  ((resource has offerableByPrincipal && resource.offerableByPrincipal) ||
   (principal has serverAdmin && principal.serverAdmin))
};

// The target Household consents through its live HouseholdAdmins; ServerAdmin may act for them.
// Lifecycle (pending, active, expiry) is the conditional transition's business, not authority's:
// a decided offer answers with its typed lifecycle error, never a policy denial.
@id("decide-pending-share-as-target-admin-or-server-admin")
permit (
  principal,
  action in
    [Streamarr::Action::"acceptProfileShare",
     Streamarr::Action::"rejectProfileShare"],
  resource
)
when {
  principal has enabled && principal.enabled &&
  ((resource has principalAdminOfTarget && resource.principalAdminOfTarget) ||
   (principal has serverAdmin && principal.serverAdmin))
};

// The offerer may withdraw while pending; so may the sovereign Account whose Personal Profile is
// on offer (ADR 0024 §Invitations); ServerAdmin may withdraw any pending offer.
@id("cancel-pending-share-as-offerer-sovereign-or-server-admin")
permit (
  principal,
  action == Streamarr::Action::"cancelProfileShare",
  resource
)
when {
  principal has enabled && principal.enabled &&
  ((resource has offeredByPrincipal && resource.offeredByPrincipal) ||
   (resource has sovereignOverProfileByPrincipal && resource.sovereignOverProfileByPrincipal) ||
   (principal has serverAdmin && principal.serverAdmin))
};

// An active non-structural share ends by target HouseholdAdmin, a direct manager who belongs to
// that Household, or the sovereign Account. ServerAdmin uses the audited administrative end.
@id("end-active-share-as-target-admin-manager-or-sovereign")
permit (
  principal,
  action == Streamarr::Action::"endProfileShare",
  resource
)
when {
  principal has enabled && principal.enabled &&
  resource has structural && !resource.structural &&
  ((resource has principalAdminOfTarget && resource.principalAdminOfTarget) ||
   (resource has directlyManagedByPrincipal && resource.directlyManagedByPrincipal &&
    resource has principalMemberOfTarget && resource.principalMemberOfTarget) ||
   (resource has sovereignOverProfileByPrincipal && resource.sovereignOverProfileByPrincipal))
};

// The administrative end is a fresh-reauthenticated ServerAdmin override with an audited reason.
@id("administratively-end-active-share-as-fresh-server-admin")
permit (
  principal,
  action == Streamarr::Action::"administrativelyEndProfileShare",
  resource
)
when {
  principal has enabled && principal.enabled &&
  resource has structural && !resource.structural &&
  principal has serverAdmin && principal.serverAdmin &&
  context.freshReauthentication
};
