package com.streamarr.server.services.events.notification;

/**
 * How someone decided a request's subject, as its kind words it: an offer is accepted or rejected,
 * an invitation accepted or declined (ADR 0063 §Requests and news). A decision stores it with the
 * request.
 */
public interface RequestAnswer {

  String name();
}
