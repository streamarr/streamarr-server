package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.support.BoundedTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

@Tag("UnitTest")
@DisplayName("Bitrate scans waiting tests")
class BitrateScansWaitingTest {

  private static final String WAITING_METRIC = "streamarr.probes.bitrate_scans_waiting";
  private static final Duration BOUND = Duration.ofSeconds(5);
  private static final Duration REFRESH_INTERVAL = Duration.ofMillis(10);

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ScanTable scanTable = new ScanTable();
  private BitrateScansWaiting waiting;

  @AfterEach
  void stopRefreshes() {
    if (waiting != null && waiting.isRunning()) {
      waiting.stop();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"count", "refreshInterval"})
  @DisplayName("Should reject the waiting bitrate scans' gauge when a required part is missing")
  void shouldRejectTheWaitingBitrateScansGaugeWhenARequiredPartIsMissing(String missing) {
    var builder = BitrateScansWaiting.builder();
    if (!missing.equals("count")) {
      builder.count(scanTable);
    }

    if (!missing.equals("refreshInterval")) {
      builder.refreshInterval(REFRESH_INTERVAL);
    }

    assertThatNullPointerException().isThrownBy(builder::build).withMessageContaining(missing);
  }

  @Test
  @DisplayName("Should report the last count when the scan table's count changes")
  void shouldReportTheLastCountWhenTheScanTablesCountChanges() throws Exception {
    startCounting(scanTable);

    scanTable.answerFromNowOn(() -> 2);
    assertThat(reported()).isEqualTo(2);
    scanTable.answerFromNowOn(() -> 5);

    assertThat(reported()).isEqualTo(5);
  }

  // The gauge would otherwise wait for the count, as the registry's last publish did while
  // PostgreSQL was stopped.
  @Test
  @DisplayName("Should report no value without waiting when the first count has not ended")
  void shouldReportNoValueWithoutWaitingWhenTheFirstCountHasNotEnded() throws Exception {
    var count = new UnendingCount();
    startCounting(count);
    count.awaitStarted();

    try (var read = BoundedTask.start(this::reported)) {
      assertThat(read.await(BOUND)).isNaN();
    } finally {
      count.release();
    }
  }

  @Test
  @DisplayName(
      "Should report no value while the count fails and the count again when a count succeeds")
  void shouldReportNoValueWhileTheCountFailsAndTheCountAgainWhenACountSucceeds() throws Exception {
    startCounting(scanTable);
    scanTable.answerFromNowOn(() -> 4);
    assertThat(reported()).isEqualTo(4);

    scanTable.answerFromNowOn(
        () -> {
          throw new DataAccessResourceFailureException("PostgreSQL is stopped");
        });
    assertThat(reported()).as("A failed count leaves no stale count").isNaN();
    scanTable.answerFromNowOn(() -> 6);

    assertThat(reported()).as("A failed count does not end the refreshes").isEqualTo(6);
  }

  // The count ignores its interrupt, as a database read on a platform thread does, so the stop
  // returns only if it does not wait for the count.
  @Test
  @DisplayName(
      "Should interrupt the count and return without waiting for it when the server stops during a"
          + " count")
  void shouldInterruptTheCountAndReturnWithoutWaitingForItWhenTheServerStopsDuringACount()
      throws Exception {
    var count = new UnendingCount();
    startCounting(count);
    count.awaitStarted();

    try {
      BoundedTask.runWithin(BOUND, waiting::stop);

      assertThat(waiting.isRunning()).isFalse();
      assertThat(count.interrupted.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
      assertThat(count.ended.getCount()).as("The stop did not wait for the count").isOne();
    } finally {
      count.release();
    }

    assertThat(count.ended.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
  }

  private void startCounting(LongSupplier count) {
    waiting = BitrateScansWaiting.builder().count(count).refreshInterval(REFRESH_INTERVAL).build();
    waiting.bindTo(registry);
    waiting.start();
  }

  private Double reported() {
    return registry.get(WAITING_METRIC).gauge().value();
  }

  /** Answers each count as the scan table would, and tells the test when counts begin. */
  private static final class ScanTable implements LongSupplier {

    private final AtomicReference<LongSupplier> answer = new AtomicReference<>(() -> 0);
    private final Semaphore begunCounts = new Semaphore(0);

    @Override
    public long getAsLong() {
      begunCounts.release();
      return answer.get().getAsLong();
    }

    /**
     * Makes every later count give {@code next}, and returns once the gauge holds the answer. The
     * first count that begins after the change reads the new answer, and the refreshes begin the
     * next count only after that count's answer is stored.
     */
    void answerFromNowOn(LongSupplier next) throws InterruptedException {
      answer.set(next);
      begunCounts.drainPermits();
      assertThat(begunCounts.tryAcquire(2, BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
    }
  }

  /** A count that waits, through any interrupt, until the test releases it. */
  private static final class UnendingCount implements LongSupplier {

    private static final Duration RELEASE_CHECK = Duration.ofMillis(5);

    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final CountDownLatch ended = new CountDownLatch(1);

    @Override
    public long getAsLong() {
      started.countDown();
      while (released.getCount() > 0) {
        LockSupport.parkNanos(RELEASE_CHECK.toNanos());
        if (Thread.interrupted()) {
          interrupted.countDown();
        }
      }

      ended.countDown();
      return 1;
    }

    void awaitStarted() throws InterruptedException {
      assertThat(started.await(BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
    }

    void release() {
      released.countDown();
    }
  }
}
