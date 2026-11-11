package com.streamarr.server.domain.notification;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import java.time.Instant;
import java.util.Optional;

/**
 * How a request resolved, read from its subject each time and never stored (ADR 0063 §Requests and
 * news). A resolved request counts as read, for every recipient at once.
 */
public enum RequestResolution {
  /** Someone with the authority accepted, rejected or declined the subject. */
  DECIDED,
  /**
   * The proposer canceled the subject, a newer proposal replaced it, or the server invalidated it.
   */
  WITHDRAWN,
  /** The subject's expiry passed with no decision. */
  EXPIRED;

  /**
   * How the request about the offer resolved at the instant, or empty while the offer waits. The
   * offer reads as expired from its expiry time on, before the sweep stores it.
   */
  public static Optional<RequestResolution> of(ProfileHouseholdShare offer, Instant now) {
    return switch (offer.statusAt(now)) {
      case PENDING -> Optional.empty();
      case ACTIVE, ENDED, REJECTED -> Optional.of(DECIDED);
      case CANCELED, INVALIDATED -> Optional.of(WITHDRAWN);
      case EXPIRED -> Optional.of(EXPIRED);
    };
  }
}
