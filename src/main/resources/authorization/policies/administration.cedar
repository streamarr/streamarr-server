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
