package com.streamarr.server.exceptions;

import java.time.Duration;

/** A throttled request that knows when a slot frees; transports surface it as retry-after. */
public interface RetryAfterAware {

  Duration retryAfter();
}
