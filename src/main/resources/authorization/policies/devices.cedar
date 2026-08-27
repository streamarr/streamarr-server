// Devices (ADR 0024 §Devices): a TV is registered to one Household context by an Account's
// approval, and a device-bound session may watch but never administer. ADR 0021's pairing
// transport (codes, budgets, cadence) stays outside Cedar; the userCode is resolved to a Grant
// before any evaluation, and codes never appear in Cedar context.

// A device-bound session administers nothing, on any resource, in any group — including linking
// further Devices. A forbid beats every permit.
@id("device-bound-sessions-never-administer")
forbid (
  principal,
  action in
    [Streamarr::Action::"serverAdministration",
     Streamarr::Action::"householdAdministration",
     Streamarr::Action::"accountAdministration",
     Streamarr::Action::"profileAdministration",
     Streamarr::Action::"invitationAdministration",
     Streamarr::Action::"shareAdministration",
     Streamarr::Action::"managerAdministration",
     Streamarr::Action::"deviceAdministration"],
  resource
)
when { principal has deviceBound && principal.deviceBound };

// Any enabled Account approves its own pairing grant; the ceremony validates the chosen
// Household and the ESN block outside Cedar, and the winning poll rechecks them live.
@id("link-device-as-enabled-account")
permit (
  principal,
  action == Streamarr::Action::"linkDevice",
  resource
)
when {
  principal has enabled && principal.enabled
};

// The registered Household's live admin or ServerAdmin revokes a registration.
@id("revoke-device-registration-as-household-admin-or-server-admin")
permit (
  principal,
  action == Streamarr::Action::"revokeDeviceRegistration",
  resource
)
when {
  principal has enabled && principal.enabled &&
  ((resource has principalAdminOfHousehold && resource.principalAdminOfHousehold) ||
   (principal has serverAdmin && principal.serverAdmin))
};

// Household-scoped ESN administration belongs to that Household's live admin or ServerAdmin.
@id("administer-household-esn-as-local-admin-or-server-admin")
permit (
  principal,
  action in
    [Streamarr::Action::"blockEsn",
     Streamarr::Action::"unblockEsn",
     Streamarr::Action::"viewDeviceAdministration"],
  resource
)
when {
  principal has enabled && principal.enabled &&
  ((principal has liveHouseholdRole && principal.liveHouseholdRole == "ADMIN" &&
    principal has liveMembershipHousehold && principal.liveMembershipHousehold == resource) ||
   (principal has serverAdmin && principal.serverAdmin))
};

// A server-wide block is a fresh-reauthenticated ServerAdmin action with an audited reason.
@id("block-esn-server-wide-as-fresh-server-admin")
permit (
  principal,
  action == Streamarr::Action::"blockEsnServerWide",
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin &&
  context.freshReauthentication
};

// Unblocking and the server-wide device view are live-ServerAdmin work.
@id("administer-server-devices-as-server-admin")
permit (
  principal,
  action in
    [Streamarr::Action::"unblockEsnServerWide",
     Streamarr::Action::"viewServerDeviceAdministration"],
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin
};
