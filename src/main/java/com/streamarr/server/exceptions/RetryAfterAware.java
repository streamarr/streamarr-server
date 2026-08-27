package com.streamarr.server.exceptions;

import java.time.Duration;

/** A throttled request that knows when a slot frees; transports surface it as retry-after. */
public interface RetryAfterAware {

  Duration retryAfter();

  /**
   * Delta-seconds rounded up and never below one: a client that retried a fraction early would just
   * be refused again.
   */
  default long retryAfterSeconds() {
    var retryAfter = retryAfter();
    if (retryAfter.isNegative() || retryAfter.isZero()) {
      return 1;
    }

    if (retryAfter.getNano() == 0 || retryAfter.getSeconds() == Long.MAX_VALUE) {
      return retryAfter.getSeconds();
    }

    return retryAfter.getSeconds() + 1;
  }
}
