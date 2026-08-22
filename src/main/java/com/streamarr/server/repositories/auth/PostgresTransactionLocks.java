package com.streamarr.server.repositories.auth;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

final class PostgresTransactionLocks {

  private PostgresTransactionLocks() {}

  static void lockNormalizedKey(DSLContext dsl, String namespace, String value) {
    var normalizedKey = DSL.concat(DSL.inline(namespace + ":"), DSL.lower(DSL.val(value.strip())));
    var keyHash =
        DSL.function(
            DSL.name("hashtextextended"), SQLDataType.BIGINT, normalizedKey, DSL.inline(0L));
    var lock = DSL.function(DSL.name("pg_advisory_xact_lock"), SQLDataType.OTHER, keyHash);
    dsl.select(lock).execute();
  }
}
