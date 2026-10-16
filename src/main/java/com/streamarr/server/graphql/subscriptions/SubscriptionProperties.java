package com.streamarr.server.graphql.subscriptions;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Limits for GraphQL subscriptions. Every limit is required and positive.
 *
 * @param operationBuffer changes one operation may hold before the server resolves them; one more
 *     ends the operation with SUBSCRIPTION_OVERFLOW
 * @param maxOperationsPerConnection operations one WebSocket may run at once
 * @param connectionSendBuffer unsent bytes one WebSocket may hold for its client; more closes the
 *     socket with 4101
 * @param deliveryFetchTimeout how long one asynchronous field of a delivered change may take to
 *     resolve; a slower field resolves as an error on that change, so later changes of the
 *     operation do not wait for the slow field
 */
@ConfigurationProperties(prefix = "graphql.subscriptions")
public record SubscriptionProperties(
    int operationBuffer,
    int maxOperationsPerConnection,
    DataSize connectionSendBuffer,
    Duration deliveryFetchTimeout) {

  public SubscriptionProperties {
    requirePositive("operation-buffer", operationBuffer);
    requirePositive("max-operations-per-connection", maxOperationsPerConnection);
    requirePositive("connection-send-buffer", connectionSendBuffer);
    requirePositive("delivery-fetch-timeout", deliveryFetchTimeout);
  }

  private static void requirePositive(String limit, int value) {
    if (value <= 0) {
      throw notPositive(limit, String.valueOf(value));
    }
  }

  private static void requirePositive(String limit, DataSize value) {
    if (value == null) {
      throw new IllegalArgumentException("graphql.subscriptions.%s is required.".formatted(limit));
    }

    if (value.toBytes() <= 0) {
      throw notPositive(limit, value.toString());
    }
  }

  private static void requirePositive(String limit, Duration value) {
    if (value == null) {
      throw new IllegalArgumentException("graphql.subscriptions.%s is required.".formatted(limit));
    }

    if (!value.isPositive()) {
      throw notPositive(limit, value.toString());
    }
  }

  private static IllegalArgumentException notPositive(String limit, String value) {
    return new IllegalArgumentException(
        "graphql.subscriptions.%s must be positive, but was %s.".formatted(limit, value));
  }
}
