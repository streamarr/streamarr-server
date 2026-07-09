package com.streamarr.server.services.auth;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
            writer.revoke(sessionId, detectedAt);
          }
        });
  }

  private static boolean transactionActive() {
    return TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive();
  }
}
