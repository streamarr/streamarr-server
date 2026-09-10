package com.streamarr.server.repositories.auth;

import com.streamarr.server.config.security.CredentialCodeProperties;
import com.streamarr.server.repositories.PostgresTransactionLocks;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
final class InvitationIssuanceLock {

  private static final String NAMESPACE = "account-invitation";

  private final PostgresTransactionLocks transactionLocks;
  private final CredentialCodeProperties properties;

  void lockRecipientEmail(String recipientEmail) {
    requireActiveTransaction();
    // The pending-email unique index and every recipient query fold case with the PostgreSQL lower
    // function. Java's toLowerCase disagrees for some code points (U+0130), which would let two
    // spellings of one address take different keys and escape serialization.
    transactionLocks.lockNormalizedKey(
        NAMESPACE, recipientEmail, properties.replacementLockTimeout());
  }

  private static void requireActiveTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Invitation issuance lock requires an active transaction.");
    }
  }
}
