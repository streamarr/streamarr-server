package com.streamarr.server.repositories.auth;

import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Writes pg_notify on the caller's transaction-bound connection. Counter changes call this from
 * transactional repository fragments; invoking it outside a transaction would lose commit-bound
 * delivery and rollback suppression.
 */
@Component
@RequiredArgsConstructor
public class PostgresCounterNotificationWriter implements CounterNotificationWriter {

  private final DSLContext dsl;

  @Override
  public void write(CounterNotificationPayload payload) {
    dsl.select(
            DSL.function(
                "pg_notify",
                String.class,
                DSL.val(CounterNotificationPayload.CHANNEL),
                DSL.val(payload.encode())))
        .fetch();
  }
}
