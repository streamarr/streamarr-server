package com.streamarr.server.domain.notification;

/** Which side ended an active share (ADR 0063 §The sharing kinds). */
public enum ProfileShareEndedBy {
  /** A ProfileManager of the Profile ended the share; the Profile's own Account counts as one. */
  OFFERING_SIDE,
  /** A HouseholdAdmin of the Household ended the share. */
  HOUSEHOLD_SIDE,
  /** A ServerAdmin ended the share, or another operation ended it on its way. */
  SERVER
}
