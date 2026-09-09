// Transfers and deletion (ADR 0024 §Transfers, §Account deletion) are audited operations.
// Transfers require current ServerAdmin authority; deletions require recent reauthentication.
// Database constraints and conditional writes preserve the final Account, required
// administrators, and eligible Profile supervision independently of authorization.

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

// Deleting an Account or administratively deleting a Profile is fresh-reauthenticated
// ServerAdmin work.
@id("delete-as-fresh-server-admin")
permit (
  principal,
  action in
    [Streamarr::Action::"administrativelyDeleteAccount",
     Streamarr::Action::"administrativelyDeleteProfile"],
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
