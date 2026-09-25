package com.streamarr.server.exceptions;

import com.streamarr.server.domain.media.ItemFailureReason;
import lombok.NonNull;

/** A probe attempt that failed; its reason is recorded and the probe is retried with backoff. */
public class ProbeExecutionException extends TranscodeException {

  private final ItemFailureReason reason;

  public ProbeExecutionException(@NonNull ItemFailureReason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public ProbeExecutionException(
      @NonNull ItemFailureReason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public ItemFailureReason reason() {
    return reason;
  }
}
