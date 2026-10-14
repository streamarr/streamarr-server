package com.streamarr.server.graphql.subscriptions;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits for GraphQL subscriptions.
 *
 * @param operationBuffer values one operation may fall behind before it ends with
 *     SUBSCRIPTION_OVERFLOW
 * @param maxOperationsPerConnection operations one WebSocket may run at once
 */
@ConfigurationProperties(prefix = "graphql.subscriptions")
public record SubscriptionProperties(int operationBuffer, int maxOperationsPerConnection) {

  public SubscriptionProperties {
    requirePositive("operation-buffer", operationBuffer);
    requirePositive("max-operations-per-connection", maxOperationsPerConnection);
  }

  private static void requirePositive(String limit, int value) {
    if (value <= 0) {
      throw new IllegalArgumentException(
          "graphql.subscriptions.%s must be positive, but was %d.".formatted(limit, value));
    }
  }
}
