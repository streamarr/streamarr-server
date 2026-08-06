package com.streamarr.server.exceptions;

import java.util.UUID;

/**
 * A statement addressed a session that has no row yet. The caller composed session creation and a
 * later write inside one transaction: JPA only queued the insert, and raw SQL does not flush it.
 * Nobody's authentication is at fault, so this must never be answered as one.
 */
public class UnwrittenAuthSessionException extends IllegalStateException {

  public UnwrittenAuthSessionException(UUID sessionId) {
    super("Auth session has no row to write to yet: " + sessionId);
  }
}
