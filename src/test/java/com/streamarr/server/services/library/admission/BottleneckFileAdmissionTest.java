package com.streamarr.server.services.library.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.http.PacedTmdbGate;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Throwaway benchmark: variant B admits by the bottlenecks' room, not by a fixed number. */
@Tag("UnitTest")
@DisplayName("POC bottleneck admission tests")
class BottleneckFileAdmissionTest {

  private static final int ITEMS = 60;

  @Test
  @DisplayName("Should keep few items in flight when every item waits on the TMDB gate")
  void shouldKeepFewItemsInFlightWhenEveryItemWaitsOnTmdbGate() throws Exception {
    var gate = new PacedTmdbGate(200, Duration.ZERO);
    var runtime = new AdmissionRuntime();
    var admission = new BottleneckFileAdmission(runtime, gate, () -> 0);
    var peakInFlight = new AtomicInteger();

    var failures =
        admission.processAll(
            Workload.SCAN,
            IntStream.range(0, ITEMS).boxed(),
            _ -> {
              peakInFlight.accumulateAndGet(runtime.inFlight(), Math::max);
              pause(Duration.ofMillis(2));
              callTmdb(gate);
              callTmdb(gate);
              return TaskOutcome.WORKED;
            });

    assertThat(failures).isEmpty();
    assertThat(runtime.completed(TaskOutcome.WORKED)).isEqualTo(ITEMS);
    assertThat(peakInFlight.get()).isBetween(1, 15);
    assertThat(runtime.inFlight()).isZero();
    assertThat(runtime.admissionLimit()).isNaN();
  }

  @Test
  @DisplayName("Should keep several items in transit when transit takes longer than a gate slot")
  void shouldKeepSeveralItemsInTransitWhenTransitTakesLongerThanGateSlot() throws Exception {
    var gate = new PacedTmdbGate(200, Duration.ZERO);
    var runtime = new AdmissionRuntime();
    var admission = new BottleneckFileAdmission(runtime, gate, () -> 0);
    var peakInTransit = new AtomicInteger();
    var items = 80;
    var transit = Duration.ofMillis(20);
    var startedNanos = System.nanoTime();

    admission.processAll(
        Workload.SCAN,
        IntStream.range(0, items).boxed(),
        _ -> {
          peakInTransit.accumulateAndGet(runtime.inTransit(), Math::max);
          pause(transit);
          callTmdb(gate);
          callTmdb(gate);
          return TaskOutcome.WORKED;
        });

    var elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);
    assertThat(peakInTransit.get()).isGreaterThanOrEqualTo(2);
    assertThat(elapsed).isLessThan(transit.multipliedBy(items));
  }

  @Test
  @DisplayName("Should not widen the transit allowance when transit time grows from queueing")
  void shouldNotWidenTransitAllowanceWhenTransitTimeGrowsFromQueueing() throws Exception {
    var gate = new PacedTmdbGate(1_000, Duration.ZERO);
    var runtime = new AdmissionRuntime();
    var admission = new BottleneckFileAdmission(runtime, gate, () -> 0);
    var database = new Semaphore(1);
    var peakInTransit = new AtomicInteger();

    admission.processAll(
        Workload.SCAN,
        IntStream.range(0, ITEMS).boxed(),
        _ -> {
          peakInTransit.accumulateAndGet(runtime.inTransit(), Math::max);
          database.acquire();
          try {
            pause(Duration.ofMillis(10));
          } finally {
            database.release();
          }

          callTmdb(gate);
          callTmdb(gate);
          return TaskOutcome.WORKED;
        });

    // One item is 2 ms of gate time and no-load transit is about 10 ms, so about (2 + 10) / 2 = 6
    // items fit in transit; the bound leaves room for sleep overshoot on a loaded host. Crediting
    // the queued transit time instead lets the allowance grow with the queue toward all 60 items.
    assertThat(peakInTransit.get()).isBetween(2, 20);
  }

  @Test
  @DisplayName("Should admit many items at once when items short-circuit before TMDB")
  void shouldAdmitManyItemsAtOnceWhenItemsShortCircuitBeforeTmdb() throws Exception {
    var gate = new PacedTmdbGate(200, Duration.ZERO);
    var runtime = new AdmissionRuntime();
    var admission = new BottleneckFileAdmission(runtime, gate, () -> 0);
    var peakInFlight = new AtomicInteger();
    var peakLimit = new AtomicInteger();

    var failures =
        admission.processAll(
            Workload.SCAN,
            IntStream.range(0, 300).boxed(),
            _ -> {
              peakInFlight.accumulateAndGet(runtime.inFlight(), Math::max);
              peakLimit.accumulateAndGet((int) runtime.admissionLimit(), Math::max);
              pause(Duration.ofMillis(3));
              return TaskOutcome.SHORT_CIRCUITED;
            });

    assertThat(failures).isEmpty();
    assertThat(runtime.completed(TaskOutcome.SHORT_CIRCUITED)).isEqualTo(300);
    assertThat(peakInFlight.get()).isGreaterThan(10);
    assertThat(peakLimit.get()).isGreaterThan(10);
  }

  @Test
  @DisplayName("Should admit nothing when Hikari has threads awaiting a connection")
  void shouldAdmitNothingWhenHikariHasThreadsAwaitingConnection() throws Exception {
    var gate = new PacedTmdbGate(200, Duration.ZERO);
    var runtime = new AdmissionRuntime();
    var polls = new AtomicInteger();
    var admission =
        new BottleneckFileAdmission(runtime, gate, () -> polls.incrementAndGet() <= 20 ? 1 : 0);
    var pollsAtFirstStart = new AtomicInteger(-1);

    admission.processAll(
        Workload.REFRESH,
        IntStream.range(0, 3).boxed(),
        _ -> {
          pollsAtFirstStart.compareAndSet(-1, polls.get());
          return TaskOutcome.WORKED;
        });

    assertThat(pollsAtFirstStart.get()).isGreaterThan(20);
    assertThat(runtime.completed(TaskOutcome.WORKED)).isEqualTo(3);
  }

  @Test
  @DisplayName("Should return failures in pull order when some items throw")
  void shouldReturnFailuresInPullOrderWhenSomeItemsThrow() throws Exception {
    var runtime = new AdmissionRuntime();
    var admission =
        new BottleneckFileAdmission(runtime, new PacedTmdbGate(200, Duration.ZERO), () -> 0);

    var failures =
        admission.processAll(
            Workload.SCAN,
            IntStream.range(0, 6).boxed(),
            item -> {
              if (item % 2 == 1) {
                pause(Duration.ofMillis(10L * (6 - item)));
                throw new IllegalStateException("item " + item);
              }

              return TaskOutcome.WORKED;
            });

    assertThat(failures)
        .extracting(Throwable::getMessage)
        .containsExactly("item 1", "item 3", "item 5");
    assertThat(runtime.failedCount()).isEqualTo(3);
    assertThat(runtime.inFlight()).isZero();
  }

  /** What the TMDB rate-limiting interceptor does for one request, plus a 10 ms round trip. */
  @Test
  @DisplayName("Should serve a concurrent refresh in turn when a scan with a larger threshold runs")
  void shouldServeAConcurrentRefreshInTurnWhenAScanWithALargerThresholdRuns() throws Exception {
    var gate = new PacedTmdbGate(100, Duration.ofSeconds(1));
    var runtime = new AdmissionRuntime();
    var admission = new BottleneckFileAdmission(runtime, gate, () -> 0);
    var scanAdmitted = new AtomicInteger();
    var refreshAdmittedWhileScanning = new AtomicInteger();
    var scanDone = new java.util.concurrent.atomic.AtomicBoolean();

    var scan =
        java.util.concurrent.CompletableFuture.runAsync(
            () -> {
              try {
                admission.processAll(
                    Workload.SCAN,
                    IntStream.range(0, 300).boxed(),
                    _ -> {
                      scanAdmitted.incrementAndGet();
                      pause(Duration.ofMillis(15));
                      callTmdb(gate);
                      callTmdb(gate);
                      return TaskOutcome.WORKED;
                    });
                scanDone.set(true);
              } catch (InterruptedException e) {
                throw new IllegalStateException(e);
              }
            });
    pause(Duration.ofMillis(1500));

    var refreshStarted = System.nanoTime();
    var failures =
        admission.processAll(
            Workload.REFRESH,
            IntStream.range(0, 20).boxed(),
            _ -> {
              if (!scanDone.get()) {
                refreshAdmittedWhileScanning.incrementAndGet();
              }

              callTmdb(gate);
              return TaskOutcome.WORKED;
            });
    var refreshWall = Duration.ofNanos(System.nanoTime() - refreshStarted);
    scan.join();

    assertThat(failures).isEmpty();
    // 600 scan requests at 100/s take about 6 s; a starved refresh would wait for all of them.
    assertThat(refreshAdmittedWhileScanning.get()).isEqualTo(20);
    assertThat(refreshWall).isLessThan(Duration.ofSeconds(3));
    assertThat(scanAdmitted.get()).isEqualTo(300);
  }

  private static void callTmdb(PacedTmdbGate gate) {
    var waitNanos = gate.reserveNanos();
    TmdbTaskSignals.markGateReservation(false);
    LockSupport.parkNanos(waitNanos);
    pause(Duration.ofMillis(10));
  }

  private static void pause(Duration duration) {
    LockSupport.parkNanos(duration.toNanos());
  }
}
