package com.streamarr.server.repositories.streaming;

import com.streamarr.server.repositories.PostgresTransactionLocks;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Serializes the writes of one stream session: its progress and its completion. Every writer takes
 * this lock before any row lock, so a progress report that arrives while the session completes
 * waits for the completion and then sees it.
 */
@Component
@RequiredArgsConstructor
final class StreamSessionLock {

  private static final String NAMESPACE = "stream-session";
  private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(5);

  private final PostgresTransactionLocks transactionLocks;

  void lock(UUID streamSessionId) {
    transactionLocks.lockNormalizedKey(NAMESPACE, streamSessionId.toString(), LOCK_TIMEOUT);
  }
}
