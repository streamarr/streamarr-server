package com.streamarr.server.repositories;

import java.time.Duration;
import java.util.function.Supplier;
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

  private static final String LOCK_TIMEOUT = "lock_timeout";

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
    dsl.setLocal(DSL.name(LOCK_TIMEOUT), DSL.inline(lockTimeout.toMillis() + "ms")).execute();
  }

  /**
   * Bounds every lock wait of {@code work}, then restores the transaction's earlier bound, so the
   * rest of the caller's transaction keeps its own. When {@code work} throws, the bound stays until
   * the transaction ends.
   */
  public <T> T withLockWaitLimit(Duration lockTimeout, Supplier<T> work) {
    var earlierLockTimeout =
        dsl.select(DSL.function("current_setting", String.class, DSL.inline(LOCK_TIMEOUT)))
            .fetchSingle()
            .value1();
    limitLockWait(lockTimeout);
    var result = work.get();
    dsl.select(
            DSL.function(
                "set_config",
                String.class,
                DSL.inline(LOCK_TIMEOUT),
                DSL.val(earlierLockTimeout),
                DSL.inline(true)))
        .execute();
    return result;
  }

  private void acquire(Field<?>... keys) {
    dsl.select(DSL.function(DSL.name("pg_advisory_xact_lock"), SQLDataType.OTHER, keys)).execute();
  }
}
