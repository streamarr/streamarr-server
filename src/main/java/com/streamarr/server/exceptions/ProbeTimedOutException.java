package com.streamarr.server.exceptions;

import com.streamarr.server.domain.media.ItemFailureReason;

/** A probe attempt that the worker did not finish before the server's timeout for it. */
public class ProbeTimedOutException extends ProbeExecutionException {

  public ProbeTimedOutException(Throwable cause) {
    super(
        ItemFailureReason.TEMPORARY,
        "The worker did not finish the probe before its timeout",
        cause);
  }
}
