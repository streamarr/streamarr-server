package com.streamarr.server.repositories.auth;

import com.streamarr.server.config.security.CredentialCodeProperties;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
final class InvitationIssuanceLock {

  private static final String NAMESPACE = "account-invitation:";

  private final DSLContext dsl;
  private final CredentialCodeProperties properties;

  void lockRecipientEmail(String recipientEmail) {
    requireActiveTransaction();
    var logicalKey = NAMESPACE + recipientEmail.strip().toLowerCase(Locale.ROOT);
    var keyHash =
        DSL.function(
            DSL.name("hashtextextended"), SQLDataType.BIGINT, DSL.val(logicalKey), DSL.inline(0L));
    var lock = DSL.function(DSL.name("pg_advisory_xact_lock"), SQLDataType.OTHER, keyHash);
    var lockTimeout = properties.replacementLockTimeout().toMillis() + "ms";
    dsl.setLocal(DSL.name("lock_timeout"), DSL.inline(lockTimeout)).execute();
    dsl.select(lock).execute();
  }

  private static void requireActiveTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Invitation issuance lock requires an active transaction.");
    }
  }
}
