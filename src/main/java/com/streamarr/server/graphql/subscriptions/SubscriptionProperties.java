package com.streamarr.server.graphql.subscriptions;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-connection limits for GraphQL subscriptions.
 *
 * @param operationBuffer values an operation may fall behind before it ends with
 *     SUBSCRIPTION_OVERFLOW
 * @param maxOperationsPerConnection operations one WebSocket may run at once
 */
@ConfigurationProperties(prefix = "graphql.subscriptions")
public record SubscriptionProperties(int operationBuffer, int maxOperationsPerConnection) {

  public SubscriptionProperties {
    if (operationBuffer <= 0) {
      operationBuffer = 64;
    }

    if (maxOperationsPerConnection <= 0) {
      maxOperationsPerConnection = 32;
    }
  }
}
