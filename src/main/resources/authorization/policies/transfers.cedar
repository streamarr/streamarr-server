// Transfers and deletion (ADR 0024 §Transfers, §Account deletion): recovery work. Transfers are
// live-ServerAdmin actions with an audit record; deletions add the fresh ceremony. Lifecycle
// safety (final Account, last admin, anchors) is the database's T1/T4/T6 and the conditional
// transitions' business — never authority's.

// Moving an Account or an unlinked Profile is live-ServerAdmin recovery work.
@id("transfer-as-server-admin")
permit (
  principal,
  action in
    [Streamarr::Action::"transferAccount",
     Streamarr::Action::"transferProfile"],
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin
};

// Deleting an Account or force-deleting a Profile is fresh-reauthenticated ServerAdmin work.
@id("delete-as-fresh-server-admin")
permit (
  principal,
  action in
    [Streamarr::Action::"deleteAccount",
     Streamarr::Action::"forceDeleteProfile"],
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has serverAdmin && principal.serverAdmin &&
  context.freshReauthentication
};

// Self-deletion: only the enabled, eligible person themself, freshly reauthenticated. The
// planner pins the resource to the caller's own Account.
@id("delete-my-account-as-fresh-eligible-self")
permit (
  principal,
  action == Streamarr::Action::"deleteMyAccount",
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal has eligible && principal.eligible &&
  context.freshReauthentication &&
  principal == resource
};
