package com.streamarr.server.services.auth;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenReuseRevoker {

  private final TokenReuseRevocationWriter writer;

  public void revokeAfterCompletion(UUID sessionId, Instant detectedAt) {
    if (!transactionActive()) {
      writer.revoke(sessionId, detectedAt);
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            // Spring swallows afterCompletion exceptions on its own logger with no session
            // context, so a failed write here would silently leave a detected-theft family
            // live. Own the failure: the revoke is idempotent, so a retry (manual or via the
            // next replay) is always safe.
            try {
              writer.revoke(sessionId, detectedAt);
            } catch (RuntimeException e) {
              log.error(
                  "SECURITY: token-reuse revocation failed for session {} — the session and its"
                      + " active refresh token remain live; revoke manually or investigate.",
                  sessionId,
                  e);
            }
          }
        });
  }

  private static boolean transactionActive() {
    return TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive();
  }
}
