package com.streamarr.server.services.events.notification;

/** How a HouseholdAdmin or a ServerAdmin answered an offer of a Profile. */
public enum ProfileShareAnswer implements RequestAnswer {
  ACCEPTED,
  REJECTED
}
