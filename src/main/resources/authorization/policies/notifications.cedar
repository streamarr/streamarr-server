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

// A sharing Notification shows to the Household's side as the share does, while the offer is
// pending and not expired or the share is active (ADR 0024). Once it is over, that side keeps only
// the Notifications addressed to it, which the changes it received made; the stored side decides,
// never the kind, so news told to the offering side stays hidden from an Account that later comes
// to administer the Household (ADR 0063 §The sharing kinds). The offering side passes through
// viewProfileShareHouseholds at every status.
@id("household-side-keeps-only-share-notifications-addressed-to-it-once-the-share-is-over")
forbid (
  principal,
  action == Streamarr::Action::"viewProfileShareNotification",
  resource
)
unless { context.shareLive || context.addressedToHouseholdSide };
