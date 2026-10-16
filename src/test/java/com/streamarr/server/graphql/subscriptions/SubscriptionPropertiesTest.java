package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

@Tag("UnitTest")
@DisplayName("Subscription Properties Tests")
class SubscriptionPropertiesTest {

  @Test
  @DisplayName("Should bind the configured limits when every limit is positive")
  void shouldBindConfiguredLimitsWhenEveryLimitIsPositive() {
    var properties =
        bind(
            Map.of(
                "operation-buffer",
                "16",
                "max-operations-per-connection",
                "4",
                "connection-send-buffer",
                "256KB",
                "delivery-fetch-timeout",
                "3s"));

    assertThat(properties.operationBuffer()).isEqualTo(16);
    assertThat(properties.maxOperationsPerConnection()).isEqualTo(4);
    assertThat(properties.connectionSendBuffer()).isEqualTo(DataSize.ofKilobytes(256));
    assertThat(properties.deliveryFetchTimeout()).isEqualTo(Duration.ofSeconds(3));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "operation-buffer",
        "max-operations-per-connection",
        "connection-send-buffer",
        "delivery-fetch-timeout"
      })
  @DisplayName("Should refuse to start when a limit is zero")
  void shouldRefuseToStartWhenLimitIsZero(String limit) {
    var limits = new HashMap<>(validLimits());
    limits.put(limit, "0");

    assertThatThrownBy(() -> bind(limits))
        .isInstanceOf(BindException.class)
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(limit);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "operation-buffer",
        "max-operations-per-connection",
        "connection-send-buffer",
        "delivery-fetch-timeout"
      })
  @DisplayName("Should refuse to start when a limit is missing")
  void shouldRefuseToStartWhenLimitIsMissing(String limit) {
    var limits = new HashMap<>(validLimits());
    limits.remove(limit);

    assertThatThrownBy(() -> bind(limits)).isInstanceOf(BindException.class);
  }

  private static Map<String, String> validLimits() {
    return Map.of(
        "operation-buffer",
        "64",
        "max-operations-per-connection",
        "32",
        "connection-send-buffer",
        "1MB",
        "delivery-fetch-timeout",
        "10s");
  }

  private static SubscriptionProperties bind(Map<String, String> limits) {
    var source = new HashMap<String, String>();
    limits.forEach((name, value) -> source.put("graphql.subscriptions." + name, value));
    return new Binder(new MapConfigurationPropertySource(source))
        .bindOrCreate("graphql.subscriptions", SubscriptionProperties.class);
  }
}
