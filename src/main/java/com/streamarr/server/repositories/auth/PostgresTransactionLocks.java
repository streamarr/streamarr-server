package com.streamarr.server.repositories.auth;

import com.streamarr.server.config.security.CredentialCodeProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
final class PostgresTransactionLocks {

  private final DSLContext dsl;
  private final CredentialCodeProperties properties;

  void lockNormalizedKey(String namespace, String value) {
    lockNormalizedKey(namespace, value, properties.replacementLockTimeout());
  }

  void lockNormalizedKey(String namespace, String value, Duration lockTimeout) {
    var normalizedKey = DSL.concat(DSL.inline(namespace + ":"), DSL.lower(DSL.val(value.strip())));
    var keyHash =
        DSL.function(
            DSL.name("hashtextextended"), SQLDataType.BIGINT, normalizedKey, DSL.inline(0L));
    var lock = DSL.function(DSL.name("pg_advisory_xact_lock"), SQLDataType.OTHER, keyHash);
    limitLockWait(lockTimeout);
    dsl.select(lock).execute();
  }

  /** Bounds every lock wait in the current transaction, not just the advisory lock's. */
  void limitLockWait(Duration lockTimeout) {
    dsl.setLocal(DSL.name("lock_timeout"), DSL.inline(lockTimeout.toMillis() + "ms")).execute();
  }
}
