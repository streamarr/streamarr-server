package com.streamarr.server.config.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.support.BoundedTask;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Recording OTLP Collector Tests")
class RecordingOtlpCollectorTest {

  private static final Duration BOUND = Duration.ofSeconds(5);

  private final RecordingOtlpCollector collector = RecordingOtlpCollector.start(BOUND);

  @AfterEach
  void stopCollector() {
    collector.close();
  }

  @Test
  @DisplayName("Should return the exports after the position when a waited-for export arrives")
  void shouldReturnTheExportsAfterThePositionWhenAWaitedForExportArrives() throws Exception {
    send(collector, exportOf("before.position"));
    var position = collector.metricExports().position();
    var conditionChecked = new CountDownLatch(1);

    try (var waiter =
        BoundedTask.start(
            () ->
                collector
                    .metricExports()
                    .awaitAfter(
                        position,
                        received -> {
                          conditionChecked.countDown();
                          return !received.isEmpty();
                        }))) {
      assertThat(conditionChecked.await(BOUND.toNanos(), TimeUnit.NANOSECONDS)).isTrue();
      send(collector, exportOf("after.position"));

      assertThat(waiter.await(BOUND))
          .extracting(OtlpExports::metricNames)
          .containsExactly(Set.of("after.position"));
    }
  }

  @Test
  @DisplayName("Should fail when no export after the position satisfies the condition in time")
  void shouldFailWhenNoExportAfterThePositionSatisfiesTheConditionInTime() throws Exception {
    try (var impatientCollector = RecordingOtlpCollector.start(Duration.ofMillis(200))) {
      send(impatientCollector, exportOf("before.position"));
      var exports = impatientCollector.metricExports();
      var position = exports.position();

      assertThatThrownBy(() -> exports.awaitAfter(position, received -> !received.isEmpty()))
          .isInstanceOf(AssertionError.class);
    }
  }

  private static void send(RecordingOtlpCollector receiver, ExportMetricsServiceRequest export)
      throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create(receiver.endpoint() + "v1/metrics"))
                  .POST(HttpRequest.BodyPublishers.ofByteArray(export.toByteArray()))
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertThat(response.statusCode()).isEqualTo(200);
    }
  }

  private static ExportMetricsServiceRequest exportOf(String metricName) {
    return ExportMetricsServiceRequest.newBuilder()
        .addResourceMetrics(
            ResourceMetrics.newBuilder()
                .addScopeMetrics(
                    ScopeMetrics.newBuilder().addMetrics(Metric.newBuilder().setName(metricName))))
        .build();
  }
}
