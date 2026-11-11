// Notifications (ADR 0063): an Account reads and marks its own Notifications, and no other
// Account's, a ServerAdmin's included. The planner pins the resource to the caller's own Account.
// Each Notification is then guarded by the read of its subject, decided for the Account from its
// stored relationships. A device-bound session receives none (devices.cedar).
@id("read-and-mark-own-notifications-as-enabled-account")
permit (
  principal,
  action in Streamarr::Action::"notifications",
  resource
)
when {
  principal has enabled && principal.enabled &&
  principal == resource
};
