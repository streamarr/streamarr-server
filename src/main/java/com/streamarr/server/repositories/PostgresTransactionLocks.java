package com.streamarr.server.repositories;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;

/** Acquires advisory locks in the caller's transaction. Numeric keys retain its lock timeout. */
@Component
@RequiredArgsConstructor
public final class PostgresTransactionLocks {

  private final DSLContext dsl;

  public void lock(long key) {
    acquire(DSL.val(key));
  }

  public void lock(int namespace, int key) {
    acquire(DSL.val(namespace), DSL.val(key));
  }

  public void lockNormalizedKey(String namespace, String value, Duration lockTimeout) {
    var normalizedKey = DSL.concat(DSL.inline(namespace + ":"), DSL.lower(DSL.val(value.strip())));
    var keyHash =
        DSL.function(
            DSL.name("hashtextextended"), SQLDataType.BIGINT, normalizedKey, DSL.inline(0L));
    limitLockWait(lockTimeout);
    acquire(keyHash);
  }

  /** Bounds every lock wait in the current transaction, not just the advisory lock's. */
  public void limitLockWait(Duration lockTimeout) {
    dsl.setLocal(DSL.name("lock_timeout"), DSL.inline(lockTimeout.toMillis() + "ms")).execute();
  }

  private void acquire(Field<?>... keys) {
    dsl.select(DSL.function(DSL.name("pg_advisory_xact_lock"), SQLDataType.OTHER, keys)).execute();
  }
}
