// Household teardown and the security audit (ADR 0024 §Teardown, §Audit): the last resorts.

// Tearing a Household down is fresh-reauthenticated ServerAdmin work with an audited reason.
@id("tear-down-household-as-fresh-server-admin")
permit (
  principal,
  action == Streamarr::Action::"tearDownHousehold",
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
