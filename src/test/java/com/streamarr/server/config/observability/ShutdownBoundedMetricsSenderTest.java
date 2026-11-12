package com.streamarr.server.config.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.support.BoundedTask;
import io.micrometer.registry.otlp.OtlpMetricsSender;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Shutdown bounded metrics sender tests")
class ShutdownBoundedMetricsSenderTest {

  // Far past the sender's one-second bound, so a wait that ends within it ended at the bound.
  private static final Duration BOUND = Duration.ofSeconds(5);
  private static final OtlpMetricsSender.Request REQUEST =
      OtlpMetricsSender.Request.builder(new byte[] {1})
          .address("http://metrics/v1/metrics")
          .build();

  private final CountDownLatch storeReached = new CountDownLatch(1);
  private final CountDownLatch sendInterrupted = new CountDownLatch(1);
  private final List<OtlpMetricsSender.Request> delivered = new CopyOnWriteArrayList<>();

  // A metrics store that takes each request and never answers, until the send is interrupted.
  private final OtlpMetricsSender silentStore =
      request -> {
        storeReached.countDown();
        try {
          new CountDownLatch(1).await();
        } catch (InterruptedException interrupted) {
          sendInterrupted.countDown();
          throw interrupted;
        }
      };

  @Test
  @DisplayName("Should end a send in flight when the server begins to stop")
  void shouldEndASendInFlightWhenTheServerBeginsToStop() throws Exception {
    var sender = new ShutdownBoundedMetricsSender(silentStore);
    try (var publish = BoundedTask.start(() -> sender.send(REQUEST))) {
      assertThat(storeReached.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();

      sender.beginShutdown();

      assertThatThrownBy(() -> publish.await(BOUND)).isInstanceOf(InterruptedException.class);
    }
  }

  @Test
  @DisplayName(
      "Should stop waiting after one second, and end the send, when the server stops and the store"
          + " does not answer")
  void shouldStopWaitingAfterOneSecondAndEndTheSendWhenTheServerStopsAndTheStoreDoesNotAnswer()
      throws Exception {
    var sender = new ShutdownBoundedMetricsSender(silentStore);
    sender.beginShutdown();

    try (var publish = BoundedTask.start(() -> sender.send(REQUEST))) {
      assertThatThrownBy(() -> publish.await(BOUND)).isInstanceOf(TimeoutException.class);
    }

    assertThat(sendInterrupted.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
  }

  // The registry sends a publish in batches, and the batches share the one second.
  @Test
  @DisplayName("Should send no more batches once the second has passed when the server stops")
  void shouldSendNoMoreBatchesOnceTheSecondHasPassedWhenTheServerStops() throws Exception {
    var sends = new AtomicInteger();
    var sender =
        new ShutdownBoundedMetricsSender(
            request -> {
              if (sends.getAndIncrement() == 0) {
                silentStore.send(request);
                return;
              }

              delivered.add(request);
            });
    sender.beginShutdown();
    try (var firstBatch = BoundedTask.start(() -> sender.send(REQUEST))) {
      assertThatThrownBy(() -> firstBatch.await(BOUND)).isInstanceOf(TimeoutException.class);
    }

    assertThatThrownBy(() -> sender.send(REQUEST)).isInstanceOf(TimeoutException.class);
    assertThat(sends).hasValue(1);
    assertThat(delivered).isEmpty();
  }

  @Test
  @DisplayName("Should deliver the last publish when the server stops and the store answers")
  void shouldDeliverTheLastPublishWhenTheServerStopsAndTheStoreAnswers() throws Exception {
    var sender = new ShutdownBoundedMetricsSender(delivered::add);
    sender.beginShutdown();

    sender.send(REQUEST);

    assertThat(delivered).containsExactly(REQUEST);
  }

  @Test
  @DisplayName("Should pass the store's failure on when the server stops")
  void shouldPassTheStoresFailureOnWhenTheServerStops() {
    var sender =
        new ShutdownBoundedMetricsSender(
            request -> {
              throw new IllegalStateException("The store refused the metrics");
            });
    sender.beginShutdown();

    assertThatThrownBy(() -> sender.send(REQUEST)).isInstanceOf(IllegalStateException.class);
  }
}
