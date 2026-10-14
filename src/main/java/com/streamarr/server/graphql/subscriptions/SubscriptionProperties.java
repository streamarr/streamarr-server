package com.streamarr.server.graphql.subscriptions;

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
 */
@ConfigurationProperties(prefix = "graphql.subscriptions")
public record SubscriptionProperties(
    int operationBuffer, int maxOperationsPerConnection, DataSize connectionSendBuffer) {

  public SubscriptionProperties {
    requirePositive("operation-buffer", operationBuffer);
    requirePositive("max-operations-per-connection", maxOperationsPerConnection);
    requirePositive("connection-send-buffer", connectionSendBuffer);
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

  private static IllegalArgumentException notPositive(String limit, String value) {
    return new IllegalArgumentException(
        "graphql.subscriptions.%s must be positive, but was %s.".formatted(limit, value));
  }
}
