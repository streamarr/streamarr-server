package com.streamarr.server.services.streaming.remote;

/**
 * A presented worker certificate failed one of the SPIFFE identity checks. The message names the
 * specific check for server-side logs; the peer only ever sees a bare {@code UNAUTHENTICATED}.
 */
public final class WorkerIdentityException extends RuntimeException {

  public WorkerIdentityException(String message) {
    super(message);
  }
}
