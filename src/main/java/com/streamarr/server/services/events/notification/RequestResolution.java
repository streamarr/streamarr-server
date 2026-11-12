package com.streamarr.server.services.events.notification;

/**
 * How a request resolved (ADR 0063 §Requests and news). The request stores it when its subject's
 * events resolve it, and reads as expired from its subject's expiry before any event says so. A
 * resolved request counts as read, for every recipient at once.
 */
public enum RequestResolution {
  /** Someone with the authority accepted, rejected or declined the subject. */
  DECIDED,
  /**
   * The proposer canceled the subject, a newer proposal replaced it, or the server invalidated it.
   */
  WITHDRAWN,
  /** The subject's expiry passed with no decision. */
  EXPIRED
}
