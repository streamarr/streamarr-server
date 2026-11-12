package com.streamarr.server.domain.notification;

/**
 * The side of a share that a sharing Notification was addressed to (ADR 0063 §The sharing kinds).
 * It is the side that the kind tells, even when the recipient also stands on the other side, and
 * nothing changes it after the Notification is written.
 */
public enum ProfileShareSide {
  /** The Profile's ProfileManagers, direct and derived, and the Account that made the offer. */
  OFFERING_SIDE,
  /** The HouseholdAdmins of the Household the Profile is offered or shared to. */
  HOUSEHOLD_SIDE
}
