// Server administration is granted only by live ServerAdmin authority on an enabled Account
// (ADR 0024 §ServerAdmin, ADR 0025). Both facts are loaded from PostgreSQL for this action group;
// tokens carry no ServerAdmin claim, so a revoked ServerAdmin is denied on the next request.
@id("server-administration-requires-live-enabled-server-admin")
permit (
  principal,
  action in Streamarr::Action::"serverAdministration",
  resource == Streamarr::Server::"streamarr"
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin
};

// A disabled Account is denied everything its live slice can prove it is disabled for; no permit
// can override this.
@id("disabled-account-is-forbidden")
forbid (principal, action, resource)
when { principal has enabled && !principal.enabled };
