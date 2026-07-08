package com.streamarr.server.repositories.auth;

import com.streamarr.server.services.auth.invalidation.CounterNotificationPayload;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Publishes a counter bump through pg_notify on the caller's connection, so the notification is
 * bound to the bumping transaction's commit.
 */
final class CounterNotificationPublisher {

  private CounterNotificationPublisher() {}

  static void publish(DSLContext dsl, CounterNotificationPayload payload) {
    dsl.select(
            DSL.function(
                "pg_notify",
                String.class,
                DSL.val(CounterNotificationPayload.CHANNEL),
                DSL.val(payload.encode())))
        .fetch();
  }
}
