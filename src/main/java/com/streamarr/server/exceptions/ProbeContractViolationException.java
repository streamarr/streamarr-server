package com.streamarr.server.exceptions;

import com.streamarr.server.domain.media.ItemFailureReason;

/**
 * A worker probe reply that breaks the worker contract. The server rejects the reply, and the same
 * worker would send the same reply for the same source again.
 */
@SuppressWarnings("java:S110") // Four of the six parents are the JDK's Throwable chain.
public class ProbeContractViolationException extends ProbeExecutionException {

  public ProbeContractViolationException(String message) {
    super(ItemFailureReason.TEMPORARY, message);
  }
}
