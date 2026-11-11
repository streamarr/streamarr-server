// Profile sharing (ADR 0024 §Profile sharing): a share makes one Profile available to a Household
// without copying data. Share-derived authority never creates another portable relationship.

// ServerAdmin or a manager offers; a self-managed Personal Profile is offered only by its own
// Account or ServerAdmin, because acceptance admits the person to the Household.
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

// The offering side sees every share of the Profile and the name of each Household it is into: its
// managers and ServerAdmin. The Account of a self-managed Personal Profile manages it; the Account
// of a supervised one does not. A HouseholdAdmin who only hosts the Profile sees only the share
// into their own Household, from that Household's side (ADR 0024).
@id("view-profile-share-households-as-manager-or-server-admin")
permit (
  principal,
  action == Streamarr::Action::"viewProfileShareHouseholds",
  resource
)
when {
  principal has enabled && principal.enabled &&
  ((resource has managedByPrincipal && resource.managedByPrincipal) ||
   (principal has serverAdmin && principal.serverAdmin))
};

// A Household's share history, every share at every status with all it names, is ServerAdmin's to
// inspect. Its HouseholdAdmins read its shares through viewHouseholdAdministration (ADR 0024).
@id("view-household-share-history-as-live-server-admin")
permit (
  principal,
  action == Streamarr::Action::"viewHouseholdShareHistory",
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin
};

// Any enabled Account may follow the changes of the shares it may read; each change is then decided
// against the reads of its own share. A device-bound session follows none (devices.cedar).
@id("view-profile-share-changes-as-enabled-account")
permit (
  principal,
  action == Streamarr::Action::"viewProfileShareChanges",
  resource
)
when {
  principal has enabled && principal.enabled
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

// A Household's live HouseholdAdmin issues share codes for it, lists them, and withdraws any of
// them, whoever issued it; ServerAdmin may for any Household (ADR 0024 §Share codes). Issuing
// invites an offer and consents to nothing: who may offer which Profile, and who accepts, stay
// with the rules above. The live role decides, never the signed one, so a code is never issued on
// authority its issuer has already lost.
@id("administer-household-share-codes-as-local-admin-or-server-admin")
permit (
  principal,
  action in
    [Streamarr::Action::"issueShareCode",
     Streamarr::Action::"viewShareCodes",
     Streamarr::Action::"withdrawShareCode"],
  resource
)
when {
  principal has enabled && principal.enabled &&
  ((principal has liveHouseholdRole && principal.liveHouseholdRole == "ADMIN" &&
    principal has liveMembershipHousehold && principal.liveMembershipHousehold == resource) ||
   (principal has serverAdmin && principal.serverAdmin))
};

// Any enabled Account may look a share code up to learn the name of the Household it names, and
// nothing else about it. The code alone decides which Household that is, so the decision reads no
// Household, and every lookup is a throttled credential attempt outside Cedar (ADR 0028). A
// device-bound session looks none up (devices.cedar).
@id("view-share-code-household-as-enabled-account")
permit (
  principal,
  action == Streamarr::Action::"viewShareCodeHousehold",
  resource
)
when {
  principal has enabled && principal.enabled
};
