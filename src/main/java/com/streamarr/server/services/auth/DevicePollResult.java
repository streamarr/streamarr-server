package com.streamarr.server.services.auth;

import lombok.NonNull;

/**
 * The outcome of one poll. Only {@link Success} is an HTTP success; the rest are grant states, not
 * failures, which is why they are modelled as results rather than exceptions — {@code
 * authorization_pending} is the normal answer to almost every poll.
 */
public sealed interface DevicePollResult {

  record Success(@NonNull AccessToken accessToken, @NonNull String rawRefreshToken)
      implements DevicePollResult {

    @Override
    public String toString() {
      return "Success[accessToken=%s, rawRefreshToken=REDACTED]".formatted(accessToken);
    }
  }

  /** Approval has not happened yet; keep polling at the interval already agreed. */
  record Pending() implements DevicePollResult {}

  /** Polled before the cadence allowed; the caller must add five seconds and keep polling. */
  record SlowDown() implements DevicePollResult {}

  record Denied() implements DevicePollResult {}

  /** Unknown, expired, already consumed, or lost the race — all indistinguishable to a client. */
  record Expired() implements DevicePollResult {}
}
