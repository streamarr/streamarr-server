package com.streamarr.server.repositories.auth;

import com.streamarr.server.config.security.CredentialCodeProperties;
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
    var normalizedKey = DSL.concat(DSL.inline(namespace + ":"), DSL.lower(DSL.val(value.strip())));
    var keyHash =
        DSL.function(
            DSL.name("hashtextextended"), SQLDataType.BIGINT, normalizedKey, DSL.inline(0L));
    var lock = DSL.function(DSL.name("pg_advisory_xact_lock"), SQLDataType.OTHER, keyHash);
    var lockTimeout = properties.replacementLockTimeout().toMillis() + "ms";
    dsl.setLocal(DSL.name("lock_timeout"), DSL.inline(lockTimeout)).execute();
    dsl.select(lock).execute();
  }
}
