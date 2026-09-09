// Household deletion and the security audit (ADR 0024 §Household deletion, §Audit): the last resorts.

// Deleting a Household is fresh-reauthenticated ServerAdmin work with an audited reason.
@id("delete-household-as-fresh-server-admin")
permit (
  principal,
  action == Streamarr::Action::"deleteHousehold",
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin &&
  context.freshReauthentication
};

// Only ServerAdmin reads the security audit.
@id("view-security-audit-as-server-admin")
permit (
  principal,
  action == Streamarr::Action::"viewSecurityAudit",
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin
};
