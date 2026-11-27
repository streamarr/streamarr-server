package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.RequestAnswer;

/** How a HouseholdAdmin or a ServerAdmin answered an offer of a Profile. */
public enum ProfileShareAnswer implements RequestAnswer {
  ACCEPTED,
  REJECTED
}
