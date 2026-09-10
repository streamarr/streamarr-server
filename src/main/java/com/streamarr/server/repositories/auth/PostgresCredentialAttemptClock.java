package com.streamarr.server.repositories.auth;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
final class PostgresCredentialAttemptClock implements CredentialAttemptClock {

  private final DSLContext dsl;

  @Override
  public Instant instant() {
    // CURRENT_TIMESTAMP is fixed at transaction start, before a target lock may have waited.
    return dsl.select(DSL.function(DSL.name("clock_timestamp"), SQLDataType.TIMESTAMPWITHTIMEZONE))
        .fetchSingle()
        .value1()
        .toInstant();
  }
}
