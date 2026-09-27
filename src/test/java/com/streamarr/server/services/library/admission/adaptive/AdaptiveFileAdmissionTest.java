package com.streamarr.server.services.library.admission.adaptive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.services.library.admission.AdmissionRuntime;
import com.streamarr.server.services.library.admission.TaskOutcome;
import com.streamarr.server.services.library.admission.TmdbTaskSignals;
import com.streamarr.server.services.library.admission.Workload;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Throwaway benchmark variant C: adaptive admission of scan and refresh items. */
@Tag("UnitTest")
@DisplayName("POC adaptive file admission tests")
class AdaptiveFileAdmissionTest {

  private final AdmissionRuntime runtime = new AdmissionRuntime();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private AdaptiveFileAdmission admission(AdaptiveLimitAlgorithm algorithm, Duration timeout) {
    return new AdaptiveFileAdmission(algorithm, runtime, timeout, registry);
  }

  private double calls(Workload workload, String status) {
    var counter =
        registry
            .find(LimiterCallMetrics.CALLS)
            .tag("workload", workload.name().toLowerCase())
            .tag("status", status)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private static Stream<Integer> counted(int size, AtomicInteger pulled) {
    return IntStream.range(0, size).boxed().peek(_ -> pulled.incrementAndGet());
  }

  @Test
  @DisplayName("Should hold in-flight at the limit and stop pulling items when tasks block")
  void shouldHoldInflightAtLimitAndStopPullingItemsWhenTasksBlock() throws Exception {
    var admission = admission(AdaptiveLimitAlgorithm.GRADIENT2, Duration.ofMinutes(1));
    var pulled = new AtomicInteger();
    var release = new CountDownLatch(1);

    var run =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return admission.processAll(
                    Workload.SCAN,
                    counted(10, pulled),
                    _ -> {
                      release.await(10, TimeUnit.SECONDS);
                      return TaskOutcome.WORKED;
                    });
              } catch (InterruptedException e) {
                throw new IllegalStateException(e);
              }
            });

    await().atMost(Duration.ofSeconds(5)).until(() -> pulled.get() == 5);
    await().atMost(Duration.ofSeconds(5)).until(() -> runtime.inFlight() == 4);
    assertThat(runtime.admissionLimit()).isEqualTo(4.0);
    assertThat(pulled).hasValue(5);

    release.countDown();
    var failures = run.get(10, TimeUnit.SECONDS);

    assertThat(failures).isEmpty();
    assertThat(pulled).hasValue(10);
    assertThat(runtime.inFlight()).isZero();
    assertThat(runtime.completed(TaskOutcome.WORKED)).isEqualTo(10);
    assertThat(calls(Workload.SCAN, "success")).isEqualTo(10);
    assertThat(calls(Workload.SCAN, "rejected")).isPositive();
  }

  @Test
  @DisplayName("Should release each permit once with the signal that matches how the task ended")
  void shouldReleaseEachPermitOnceWithSignalThatMatchesHowTaskEnded() throws Exception {
    var admission = admission(AdaptiveLimitAlgorithm.VEGAS, Duration.ofMinutes(1));
    var poolTimeout = new RuntimeException(new SQLTransientConnectionException("pool timeout"));
    var tmdbTimeout = new HttpTimeoutException("request timed out");
    var programmingError = new IllegalStateException("boom");

    var failures =
        admission.processAll(
            Workload.REFRESH,
            Stream.of(
                "worked",
                "short",
                "error",
                "caught-timeout",
                "retried",
                "pool-timeout",
                "tmdb-timeout"),
            item ->
                switch (item) {
                  case "worked" -> TaskOutcome.WORKED;
                  case "short" -> TaskOutcome.SHORT_CIRCUITED;
                  case "error" -> throw programmingError;
                  case "caught-timeout" -> {
                    AdaptiveDropSignal.markDropped();
                    yield TaskOutcome.WORKED;
                  }
                  case "retried" -> {
                    TmdbTaskSignals.markRetried();
                    yield TaskOutcome.WORKED;
                  }
                  case "pool-timeout" -> throw poolTimeout;
                  case "tmdb-timeout" -> throw tmdbTimeout;
                  default -> throw new IllegalArgumentException(item);
                });

    assertThat(failures).containsExactly(programmingError, poolTimeout, tmdbTimeout);
    assertThat(calls(Workload.REFRESH, "success")).isEqualTo(1);
    assertThat(calls(Workload.REFRESH, "ignored")).isEqualTo(2);
    assertThat(calls(Workload.REFRESH, "dropped")).isEqualTo(4);
    assertThat(calls(Workload.SCAN, "success")).isZero();
    assertThat(runtime.inFlight()).isZero();
    assertThat(runtime.failedCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("Should admit again up to the limit when every earlier permit was released")
  void shouldAdmitAgainUpToLimitWhenEveryEarlierPermitWasReleased() throws Exception {
    var admission = admission(AdaptiveLimitAlgorithm.VEGAS, Duration.ofMillis(200));
    var outcomes = List.of(TaskOutcome.WORKED, TaskOutcome.SHORT_CIRCUITED);

    for (var round = 0; round < 3; round++) {
      var failures =
          admission.processAll(
              Workload.SCAN,
              IntStream.range(0, 50).boxed(),
              item -> {
                if (item % 7 == 0) {
                  throw new IllegalStateException("item " + item);
                }

                return outcomes.get(item % 2);
              });

      assertThat(failures).hasSize(8).allMatch(IllegalStateException.class::isInstance);
    }

    var concurrent = new AtomicInteger();
    var peak = new AtomicInteger();
    var failures =
        admission.processAll(
            Workload.SCAN,
            IntStream.range(0, 40).boxed(),
            _ -> {
              peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
              new CountDownLatch(1).await(5, TimeUnit.MILLISECONDS);
              concurrent.decrementAndGet();
              return TaskOutcome.SHORT_CIRCUITED;
            });

    assertThat(failures).isEmpty();
    assertThat(peak.get()).isBetween(1, (int) runtime.admissionLimit());
    assertThat(runtime.inFlight()).isZero();
  }

  @Test
  @DisplayName("Should fail the run after admitted items finish when no permit frees in time")
  void shouldFailRunAfterAdmittedItemsFinishWhenNoPermitFreesInTime() throws Exception {
    var admission = admission(AdaptiveLimitAlgorithm.GRADIENT2, Duration.ofMillis(20));
    var pulled = new AtomicInteger();
    var finished = new AtomicInteger();

    var failures =
        admission.processAll(
            Workload.SCAN,
            counted(10, pulled),
            _ -> {
              new CountDownLatch(1).await(300, TimeUnit.MILLISECONDS);
              finished.incrementAndGet();
              return TaskOutcome.WORKED;
            });

    assertThat(failures).singleElement().isInstanceOf(AdaptiveAdmissionTimeoutException.class);
    assertThat(pulled).hasValue(5);
    assertThat(finished).hasValue(4);
    assertThat(runtime.inFlight()).isZero();
  }

  @Test
  @DisplayName(
      "Should rethrow the interrupt and cancel admitted items when the walker is interrupted")
  void shouldRethrowInterruptAndCancelAdmittedItemsWhenWalkerIsInterrupted() throws Exception {
    var admission = admission(AdaptiveLimitAlgorithm.VEGAS, Duration.ofMinutes(1));
    var pulled = new AtomicInteger();
    var never = new CountDownLatch(1);
    var interruptedFlagAfterThrow = new AtomicBoolean();
    var thrown = new CompletableFuture<Throwable>();

    var walker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    admission.processAll(
                        Workload.SCAN,
                        counted(10, pulled),
                        _ -> {
                          never.await();
                          return TaskOutcome.WORKED;
                        });
                    thrown.complete(null);
                  } catch (InterruptedException e) {
                    interruptedFlagAfterThrow.set(Thread.currentThread().isInterrupted());
                    thrown.complete(e);
                  }
                });

    await().atMost(Duration.ofSeconds(5)).until(() -> pulled.get() == 5);
    await().atMost(Duration.ofSeconds(5)).until(() -> runtime.inFlight() == 4);
    walker.interrupt();

    assertThat(thrown.get(10, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
    assertThat(interruptedFlagAfterThrow).isTrue();
    assertThat(runtime.inFlight()).isZero();
    assertThat(runtime.failedCount()).isEqualTo(4);
    assertThat(calls(Workload.SCAN, "ignored")).isEqualTo(4);
  }

  @Test
  @DisplayName("Should propagate the walk's own failure after admitted items finish")
  void shouldPropagateWalksOwnFailureAfterAdmittedItemsFinish() {
    var admission = admission(AdaptiveLimitAlgorithm.GRADIENT2, Duration.ofMinutes(1));
    var walkFailure = new java.io.UncheckedIOException(new java.io.IOException("walk"));
    var items =
        Stream.of(1, 2, 3)
            .peek(
                item -> {
                  if (item == 3) {
                    throw walkFailure;
                  }
                });

    var thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> admission.processAll(Workload.SCAN, items, _ -> TaskOutcome.WORKED));

    assertThat(thrown).isSameAs(walkFailure);
    assertThat(runtime.completed(TaskOutcome.WORKED)).isEqualTo(2);
    assertThat(runtime.inFlight()).isZero();
  }
}
