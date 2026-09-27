package com.streamarr.server.services.library.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.http.PacedTmdbGate;
import com.streamarr.server.poc.HikariLive;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Throwaway benchmark: variant D's stage handoffs, gates, failure order and cancellation. */
@Tag("UnitTest")
@DisplayName("POC pipeline admission tests")
class PipelineFileAdmissionTest {

  private final AdmissionRuntime runtime = new AdmissionRuntime();
  private final AtomicInteger poolWaiters = new AtomicInteger();
  private String registerMode = "serial";
  private final HikariLive hikari =
      new HikariLive(new NoDataSource()) {
        @Override
        public int awaiting() {
          return poolWaiters.get();
        }
      };

  private PipelineFileAdmission admission(PacedTmdbGate gate) {
    return new PipelineFileAdmission(
        runtime, gate, hikari, "", Duration.ofMillis(250), registerMode);
  }

  private static PacedTmdbGate gate(double rate) {
    return new PacedTmdbGate(rate, Duration.ZERO);
  }

  @Test
  @DisplayName("Should run every item once and return failures in pull order when stages fail")
  void shouldRunEveryItemOnceAndReturnFailuresInPullOrderWhenStagesFail() throws Exception {
    var task = new FakeTask(null);
    task.shortCircuit = Set.of(3, 7);
    task.failRegister = Set.of(10);
    task.failIdentify = Set.of(20);
    task.failPersist = Set.of(30);

    var failures = admission(gate(1_000_000)).processAll(Workload.SCAN, items(50, null), task);

    assertThat(failures)
        .extracting(Throwable::getMessage)
        .containsExactly("register 10", "identify 20", "persist 30");
    assertThat(task.registered).hasSize(50).allSatisfy((_, calls) -> assertThat(calls).hasValue(1));
    assertThat(task.identified).hasSize(47).allSatisfy((_, calls) -> assertThat(calls).hasValue(1));
    assertThat(task.persisted).hasSize(46).allSatisfy((_, calls) -> assertThat(calls).hasValue(1));
    assertThat(runtime.completed(TaskOutcome.WORKED)).isEqualTo(45);
    assertThat(runtime.completed(TaskOutcome.SHORT_CIRCUITED)).isEqualTo(2);
    assertThat(runtime.failedCount()).isEqualTo(3);
    assertThat(runtime.inFlight()).isZero();
  }

  @Test
  @DisplayName("Should run every item once and keep pull order when register fans out")
  void shouldRunEveryItemOnceAndKeepPullOrderWhenRegisterFansOut() throws Exception {
    registerMode = "fanout";
    shouldRunEveryItemOnceAndReturnFailuresInPullOrderWhenStagesFail();
  }

  @Test
  @DisplayName("Should register concurrently when register fans out and the pool is idle")
  void shouldRegisterConcurrentlyWhenRegisterFansOutAndThePoolIsIdle() throws Exception {
    registerMode = "fanout";
    var task = new FakeTask(null);
    task.shortCircuit = Set.copyOf(java.util.stream.IntStream.range(0, 100).boxed().toList());
    task.registerDelay = Duration.ofMillis(10);

    var start = System.nanoTime();
    var failures = admission(gate(35)).processAll(Workload.SCAN, items(100, null), task);
    var elapsed = Duration.ofNanos(System.nanoTime() - start);

    assertThat(failures).isEmpty();
    // Serial registration alone would take 100 x 10 ms.
    assertThat(elapsed).isLessThan(Duration.ofMillis(500));
    assertThat(task.peakRegister.get()).isGreaterThan(1);
    assertThat(runtime.completed(TaskOutcome.SHORT_CIRCUITED)).isEqualTo(100);
  }

  @Test
  @DisplayName("Should keep registrations below the pool's spare connections when register fans out")
  void shouldKeepRegistrationsBelowThePoolsSpareConnectionsWhenRegisterFansOut() throws Exception {
    var smallPool =
        new HikariLive(new NoDataSource()) {
          @Override
          public int awaiting() {
            return 0;
          }

          @Override
          public int spare() {
            return 4;
          }
        };
    var admission =
        new PipelineFileAdmission(
            runtime, gate(1_000_000), smallPool, "", Duration.ofMillis(250), "fanout");
    var task = new FakeTask(null);
    task.shortCircuit = Set.copyOf(java.util.stream.IntStream.range(0, 100).boxed().toList());
    task.registerDelay = Duration.ofMillis(10);

    var failures = admission.processAll(Workload.SCAN, items(100, null), task);

    assertThat(failures).isEmpty();
    assertThat(task.peakRegister.get()).isBetween(2, 4);
    assertThat(runtime.completed(TaskOutcome.SHORT_CIRCUITED)).isEqualTo(100);
  }

  @Test
  @DisplayName("Should stop the walk before register when register fans out and the pool has waiters")
  void shouldStopTheWalkBeforeRegisterWhenRegisterFansOutAndThePoolHasWaiters() {
    registerMode = "fanout";
    poolWaiters.set(1);
    var pulled = new AtomicInteger();
    var task = new FakeTask(null);
    var admission = admission(gate(35));

    var run = runAsync(() -> admission.processAll(Workload.SCAN, items(100, pulled), task));

    await()
        .during(Duration.ofMillis(500))
        .atMost(Duration.ofSeconds(3))
        .until(() -> pulled.get() <= 1 && task.registered.isEmpty());

    poolWaiters.set(0);
    assertThat(run.join()).isEmpty();
    assertThat(runtime.completed(TaskOutcome.WORKED)).isEqualTo(100);
    assertThat(runtime.inFlight()).isZero();
  }

  @Test
  @DisplayName("Should stop the walk when the database pool has waiters")
  void shouldStopTheWalkWhenTheDatabasePoolHasWaiters() {
    poolWaiters.set(1);
    var pulled = new AtomicInteger();
    var task = new FakeTask(null);
    var admission = admission(gate(35));

    var run = runAsync(() -> admission.processAll(Workload.SCAN, items(100, pulled), task));

    await()
        .during(Duration.ofMillis(500))
        .atMost(Duration.ofSeconds(3))
        .until(() -> pulled.get() <= 3 && runtime.inFlight() <= 3);
    assertThat(task.persisted).isEmpty();

    poolWaiters.set(0);
    assertThat(run.join()).isEmpty();
    assertThat(pulled).hasValue(100);
    assertThat(runtime.completed(TaskOutcome.WORKED)).isEqualTo(100);
    assertThat(runtime.inFlight()).isZero();
  }

  @Test
  @DisplayName("Should bound identify concurrency by the TMDB schedule while keeping the gate busy")
  void shouldBoundIdentifyConcurrencyByTheTmdbScheduleWhileKeepingTheGateBusy() {
    var gate = gate(100);
    var task = new FakeTask(gate);
    var admission = admission(gate);
    var peakLimit = new AtomicInteger();
    var sampler =
        Thread.ofVirtual()
            .start(
                () -> {
                  while (!Thread.currentThread().isInterrupted()) {
                    peakLimit.accumulateAndGet((int) runtime.admissionLimit(), Math::max);
                    Thread.onSpinWait();
                  }
                });

    var start = System.nanoTime();
    var failures = runAsync(() -> admission.processAll(Workload.SCAN, items(60, null), task)).join();
    var elapsed = Duration.ofNanos(System.nanoTime() - start);
    sampler.interrupt();

    assertThat(failures).isEmpty();
    // 120 requests at 100/s cannot finish before 1.2 s; a busy gate finishes soon after.
    assertThat(elapsed).isBetween(Duration.ofMillis(1150), Duration.ofMillis(1800));
    assertThat(task.peakIdentify.get()).isBetween(2, 8);
    assertThat(peakLimit.get()).isBetween(2, 8);
    assertThat(runtime.completed(TaskOutcome.WORKED)).isEqualTo(60);
  }

  @Test
  @DisplayName("Should propagate the walk failure after admitted items finish")
  void shouldPropagateTheWalkFailureAfterAdmittedItemsFinish() {
    var walkFailure = new UncheckedIOException(new IOException("walk broke"));
    var items =
        Stream.iterate(0, i -> i + 1)
            .peek(
                i -> {
                  if (i == 5) {
                    throw walkFailure;
                  }
                });
    var task = new FakeTask(null);

    var thrown = new AtomicReference<Throwable>();
    try {
      admission(gate(1_000_000)).processAll(Workload.SCAN, items, task);
    } catch (Throwable failure) {
      thrown.set(failure);
    }

    assertThat(thrown.get()).isSameAs(walkFailure);
    assertThat(runtime.completed(TaskOutcome.WORKED)).isEqualTo(5);
    assertThat(runtime.inFlight()).isZero();
  }

  @Test
  @DisplayName("Should cancel every stage when the caller is interrupted")
  void shouldCancelEveryStageWhenTheCallerIsInterrupted() throws Exception {
    poolWaiters.set(1);
    var task = new FakeTask(null);
    var admission = admission(gate(35));
    var thrown = new AtomicReference<Throwable>();

    var caller =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    admission.processAll(Workload.SCAN, items(100, null), task);
                  } catch (Throwable failure) {
                    thrown.set(failure);
                  }
                });
    await().atMost(Duration.ofSeconds(3)).until(() -> runtime.inFlight() == 3);

    caller.interrupt();
    caller.join(Duration.ofSeconds(5));

    assertThat(caller.isAlive()).isFalse();
    assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
    assertThat(runtime.inFlight()).isZero();
    assertThat(task.persisted).isEmpty();
  }

  @Test
  @DisplayName("Should return an Error as the item's failure when any stage call throws one")
  void shouldReturnAnErrorAsTheItemsFailureWhenAnyStageCallThrowsOne() {
    for (var mode : java.util.List.of("serial", "fanout")) {
      registerMode = mode;
      var task = new FakeTask(null);
      task.errorRegister = Set.of(2);
      task.errorIdentify = Set.of(4);
      task.errorPersist = Set.of(6);
      var admission = admission(gate(1_000_000));

      var failures =
          runAsync(() -> admission.processAll(Workload.SCAN, items(10, null), task))
              .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
              .join();

      assertThat(failures)
          .extracting(Throwable::getMessage)
          .containsExactly("register error 2", "identify error 4", "persist error 6");
      assertThat(runtime.inFlight()).isZero();
    }
  }

  @Test
  @DisplayName("Should keep identifying in a later run when an earlier identify threw an Error")
  void shouldKeepIdentifyingInALaterRunWhenAnEarlierIdentifyThrewAnError() {
    var admission = admission(gate(1_000_000));
    var bad = new FakeTask(null);
    bad.errorIdentify = Set.of(0);
    runAsync(() -> admission.processAll(Workload.SCAN, items(5, null), bad))
        .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .join();

    var healthy = new FakeTask(null);
    var failures =
        runAsync(() -> admission.processAll(Workload.REFRESH, items(5, null), healthy))
            .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .join();

    assertThat(failures).isEmpty();
    assertThat(healthy.persisted).hasSize(5);
    assertThat(runtime.admissionLimit()).isZero();
  }

  @Test
  @DisplayName("Should fail every unfinished item and return when a stage thread dies")
  void shouldFailEveryUnfinishedItemAndReturnWhenAStageThreadDies() {
    var readings = new AtomicInteger();
    var dying =
        new HikariLive(new NoDataSource()) {
          @Override
          public int awaiting() {
            // The persist dispatcher reads the pool before each fork; the third reading kills it.
            if (readings.incrementAndGet() == 3) {
              throw new StackOverflowError("persist dispatcher died");
            }

            return 0;
          }
        };
    var admission =
        new PipelineFileAdmission(
            runtime, gate(1_000_000), dying, "", Duration.ofMillis(250), "serial");
    var task = new FakeTask(null);

    var failures =
        runAsync(() -> admission.processAll(Workload.SCAN, items(20, null), task))
            .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .join();

    assertThat(failures).isNotEmpty();
    assertThat(failures.getLast()).hasMessage("persist dispatcher died");
    assertThat(runtime.inFlight()).isZero();
    assertThat(runtime.failedCount()).isEqualTo(failures.size() - 1);
  }

  @Test
  @DisplayName("Should keep identifying when background artwork holds the TMDB gate")
  void shouldKeepIdentifyingWhenBackgroundArtworkHoldsTheTmdbGate() {
    var gate = gate(35);
    var stop = new java.util.concurrent.atomic.AtomicBoolean();
    var background = new java.util.ArrayList<Thread>();
    for (var i = 0; i < 4; i++) {
      background.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    while (!stop.get()) {
                      gate.awaitBackgroundSlot(false);
                      try {
                        Thread.sleep(20);
                      } catch (InterruptedException _) {
                        return;
                      }
                    }
                  }));
    }

    try {
      var task = new FakeTask(gate);
      var failures =
          runAsync(() -> admission(gate).processAll(Workload.SCAN, items(20, null), task))
              .orTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
              .join();

      assertThat(failures).isEmpty();
      assertThat(task.identified).hasSize(20);
    } finally {
      stop.set(true);
      background.forEach(Thread::interrupt);
    }
  }

  private interface Body<V> {
    V call() throws Exception;
  }

  private static <V> CompletableFuture<V> runAsync(Body<V> body) {
    var future = new CompletableFuture<V>();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                future.complete(body.call());
              } catch (Throwable failure) {
                future.completeExceptionally(failure);
              }
            });
    return future;
  }

  private static Stream<Integer> items(int count, AtomicInteger pulled) {
    return Stream.iterate(0, i -> i < count, i -> i + 1)
        .peek(
            _ -> {
              if (pulled != null) {
                pulled.incrementAndGet();
              }
            });
  }

  /** Records stage calls; identify spends two gate slots plus 20 ms per slot when given a gate. */
  private static final class FakeTask implements StagedTask<Integer, Integer, Integer> {
    private final PacedTmdbGate gate;
    Set<Integer> shortCircuit = Set.of();
    Set<Integer> failRegister = Set.of();
    Set<Integer> failIdentify = Set.of();
    Set<Integer> failPersist = Set.of();
    Set<Integer> errorRegister = Set.of();
    Set<Integer> errorIdentify = Set.of();
    Set<Integer> errorPersist = Set.of();
    Duration registerDelay = Duration.ZERO;
    final AtomicInteger registering = new AtomicInteger();
    final AtomicInteger peakRegister = new AtomicInteger();
    final ConcurrentHashMap<Integer, AtomicInteger> registered = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Integer, AtomicInteger> identified = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Integer, AtomicInteger> persisted = new ConcurrentHashMap<>();
    final AtomicInteger identifying = new AtomicInteger();
    final AtomicInteger peakIdentify = new AtomicInteger();

    FakeTask(PacedTmdbGate gate) {
      this.gate = gate;
    }

    @Override
    public TaskOutcome run(Integer item) {
      throw new UnsupportedOperationException("the pipeline calls stages");
    }

    @Override
    public Optional<Integer> register(Integer item) throws InterruptedException {
      count(registered, item);
      peakRegister.accumulateAndGet(registering.incrementAndGet(), Math::max);
      try {
        Thread.sleep(registerDelay);
        if (errorRegister.contains(item)) {
          throw new StackOverflowError("register error " + item);
        }

        if (failRegister.contains(item)) {
          throw new IllegalStateException("register " + item);
        }

        return shortCircuit.contains(item) ? Optional.empty() : Optional.of(item);
      } finally {
        registering.decrementAndGet();
      }
    }

    @Override
    public Integer identify(Integer item) throws InterruptedException {
      count(identified, item);
      peakIdentify.accumulateAndGet(identifying.incrementAndGet(), Math::max);
      try {
        if (errorIdentify.contains(item)) {
          throw new StackOverflowError("identify error " + item);
        }

        if (failIdentify.contains(item)) {
          throw new IllegalStateException("identify " + item);
        }

        if (gate != null) {
          for (var call = 0; call < 2; call++) {
            var wait = gate.reserveNanos();
            TmdbTaskSignals.markGateReservation(false);
            Thread.sleep(Duration.ofNanos(wait).plusMillis(20));
          }
        }

        return item;
      } finally {
        identifying.decrementAndGet();
      }
    }

    @Override
    public void persist(Integer item) {
      count(persisted, item);
      if (errorPersist.contains(item)) {
        throw new OutOfMemoryError("persist error " + item);
      }

      if (failPersist.contains(item)) {
        throw new IllegalStateException("persist " + item);
      }
    }

    private static void count(ConcurrentHashMap<Integer, AtomicInteger> calls, Integer item) {
      calls.computeIfAbsent(item, _ -> new AtomicInteger()).incrementAndGet();
    }
  }

  /** Not a Hikari pool; the test overrides the pool reading instead. */
  private static final class NoDataSource implements DataSource {
    @Override
    public Connection getConnection() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Connection getConnection(String username, String password) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(Class<T> iface) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
