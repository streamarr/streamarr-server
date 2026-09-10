package com.streamarr.server.repositories.auth;

import java.time.Instant;

/** The shared time source for journal admission and completion. */
@FunctionalInterface
interface CredentialAttemptClock {

  Instant instant();
}
