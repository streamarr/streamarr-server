package com.streamarr.server.services.library.admission.adaptive;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.services.library.admission.AdmissionRuntime;
import com.streamarr.server.services.library.admission.AdmittedTask;
import com.streamarr.server.services.library.admission.TaskOutcome;
import com.streamarr.server.services.library.admission.Workload;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Throwaway benchmark variant C1S (structured task scope) against C1 (virtual-thread executor): the
 * same limiter, releases and accounting, a different fork/join.
 */
@Tag("UnitTest")
@DisplayName("POC structured-scope adaptive admission tests")
class ScopedAdaptiveFileAdmissionTest {

  private static final Duration WAIT = Duration.ofSeconds(10);

  private final AdmissionRuntime runtime = new AdmissionRuntime();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  enum ForkJoin {
    C1 {
      @Override
      AdaptiveFileAdmission create(
          AdmissionRuntime runtime, MeterRegistry registry, Duration acquireTimeout) {
        return new AdaptiveFileAdmission(
            AdaptiveLimitAlgorithm.GRADIENT2, runtime, acquireTimeout, registry);
      }
    },
    C1S {
      @Override
      AdaptiveFileAdmission create(
          AdmissionRuntime runtime, MeterRegistry registry, Duration acquireTimeout) {
        return new ScopedAdaptiveFileAdmission(
            AdaptiveLimitAlgorithm.GRADIENT2, runtime, acquireTimeout, registry);
      }
    };

    abstract AdaptiveFileAdmission create(
        AdmissionRuntime runtime, MeterRegistry registry, Duration acquireTimeout);
  }

  private AdaptiveFileAdmission admission(ForkJoin forkJoin) {
    return forkJoin.create(runtime, registry, Duration.ofMinutes(1));
  }

  private ScopedAdaptiveFileAdmission scoped() {
    return (ScopedAdaptiveFileAdmission) admission(ForkJoin.C1S);
  }

  private double calls(String status) {
    var counter =
        registry
            .find(LimiterCallMetrics.CALLS)
            .tag("workload", "scan")
            .tag("status", status)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private double released() {
    return calls("success") + calls("ignored") + calls("dropped");
  }

  private static Stream<Integer> counted(int size, AtomicInteger pulled) {
    return IntStream.range(0, size).boxed().peek(_ -> pulled.incrementAndGet());
  }

  /** What a run returned or threw, and its owner's interrupt flag when it ended. */
  private record RunResult(List<Throwable> failures, Throwable thrown, boolean interruptFlag) {}

  private record Run(Thread owner, CompletableFuture<RunResult> result) {
    RunResult await() throws Exception {
      return result.get(WAIT.toSeconds(), SECONDS);
    }
  }

  private static <T> Run start(
      AdaptiveFileAdmission admission, Stream<T> items, AdmittedTask<T> task) {
    var result = new CompletableFuture<RunResult>();
    var owner =
        Thread.ofPlatform()
            .name("scan-owner")
            .start(
                () -> {
                  try {
                    var failures = admission.processAll(Workload.SCAN, items, task);
                    result.complete(
                        new RunResult(failures, null, Thread.currentThread().isInterrupted()));
                  } catch (Throwable thrown) {
                    result.complete(
                        new RunResult(List.of(), thrown, Thread.currentThread().isInterrupted()));
                  }
                });
    return new Run(owner, result);
  }

  @Test
  @DisplayName(
      "Should stop admitting, finish every started item uninterrupted and leak no permit when a stop is requested mid-run")
  void shouldStopAdmittingFinishStartedItemsUninterruptedAndLeakNoPermitWhenStopIsRequestedMidRun()
      throws Exception {
    var admission = scoped();
    var items = 1_000;
    var pulled = new AtomicInteger();
    var started = new AtomicInteger();
    var finished = new AtomicInteger();
    var cancelled = new AtomicInteger();
    var running = new AtomicInteger();

    var run =
        start(
            admission,
            counted(items, pulled),
            item -> {
              started.incrementAndGet();
              running.incrementAndGet();
              try {
                Thread.sleep(20 + (item % 7) * 30L);
                finished.incrementAndGet();
                return TaskOutcome.WORKED;
              } catch (InterruptedException e) {
                cancelled.incrementAndGet();
                throw e;
              } finally {
                running.decrementAndGet();
              }
            });

    await().atMost(WAIT).until(() -> finished.get() >= 20);
    var stopAt = System.nanoTime();
    admission.requestStop();
    var result = run.await();
    var stopToReturn = Duration.ofNanos(System.nanoTime() - stopAt);

    System.out.printf(
        "[evidence] stop mid-run: stop->return %d ms, pulled %d, started %d, finished %d,"
            + " cancelled %d, limit %s, limiter inflight %d, released %s%n",
        stopToReturn.toMillis(),
        pulled.get(),
        started.get(),
        finished.get(),
        cancelled.get(),
        runtime.admissionLimit(),
        admission.limiter(Workload.SCAN).inflight(),
        released());
    assertThat(result.thrown()).isNull();
    assertThat(result.failures()).singleElement().isInstanceOf(AdmissionStoppedException.class);
    assertThat(stopToReturn).isLessThan(Duration.ofSeconds(1));
    assertThat(started.get()).isLessThan(items).isLessThanOrEqualTo(pulled.get());
    assertThat(running).hasValue(0);
    assertThat(cancelled).hasValue(0);
    assertThat(finished).hasValue(started.get());
    assertThat(runtime.failedCount()).isZero();
    assertThat(admission.limiter(Workload.SCAN).inflight()).isZero();
    assertThat(runtime.inFlight()).isZero();
    assertThat(released()).isGreaterThanOrEqualTo(started.get());
  }

  @Test
  @DisplayName(
      "Should return only after the in-flight items finish uninterrupted, with their failures in pull order and then the stop, when a stop lands while admission waits for a permit")
  void shouldDrainInFlightItemsAndReportTheirFailuresThenStopWhenStopLandsWhileWaitingForPermit()
      throws Exception {
    var admission = scoped();
    var pulled = new AtomicInteger();
    var releaseFirst = new CountDownLatch(1);
    var releaseRest = new CountDownLatch(1);
    var secondFailure = new IllegalStateException("item 2");
    var finished = new AtomicInteger();
    var interrupted = new AtomicInteger();

    var run =
        start(
            admission,
            counted(10, pulled),
            item -> {
              try {
                (item == 0 ? releaseFirst : releaseRest).await();
              } catch (InterruptedException e) {
                interrupted.incrementAndGet();
                throw e;
              }

              if (item == 2) {
                throw secondFailure;
              }

              finished.incrementAndGet();
              return TaskOutcome.WORKED;
            });

    await().atMost(WAIT).until(() -> pulled.get() == 5 && runtime.inFlight() == 4);
    admission.requestStop();
    releaseFirst.countDown();
    await().atMost(WAIT).until(() -> finished.get() == 1);

    assertThatThrownBy(() -> run.result().get(300, MILLISECONDS))
        .as("a stop drains: the run waits for the items still in flight")
        .isInstanceOf(TimeoutException.class);
    assertThat(interrupted).hasValue(0);
    assertThat(pulled).hasValue(5);

    releaseRest.countDown();
    var result = run.await();

    System.out.printf(
        "[evidence] stop while waiting for a permit: failures %s, finished %d, interrupted %d,"
            + " pulled %d, failed tickets %d, limiter inflight %d, released %s%n",
        result.failures(),
        finished.get(),
        interrupted.get(),
        pulled.get(),
        runtime.failedCount(),
        admission.limiter(Workload.SCAN).inflight(),
        released());
    assertThat(result.thrown()).isNull();
    assertThat(result.failures()).hasSize(2);
    assertThat(result.failures().getFirst()).isSameAs(secondFailure);
    assertThat(result.failures().getLast()).isInstanceOf(AdmissionStoppedException.class);
    assertThat(finished).hasValue(3);
    assertThat(interrupted).hasValue(0);
    assertThat(pulled).hasValue(5);
    assertThat(runtime.completed(TaskOutcome.WORKED)).isEqualTo(3);
    assertThat(runtime.failedCount()).isEqualTo(1);
    assertThat(admission.limiter(Workload.SCAN).inflight()).isZero();
    assertThat(runtime.inFlight()).isZero();
    assertThat(released()).isEqualTo(5);
  }

  @Test
  @DisplayName(
      "Should report only the items' own outcomes and interrupt none when a stop lands after every item was admitted")
  void shouldReportOnlyItemOutcomesAndInterruptNoneWhenStopLandsAfterEveryItemWasAdmitted()
      throws Exception {
    var admission = scoped();
    var releaseFirst = new CountDownLatch(1);
    var releaseRest = new CountDownLatch(1);
    var lastFailure = new IllegalStateException("item 2");
    var started = new AtomicInteger();
    var finished = new AtomicInteger();
    Set<Integer> interrupted = ConcurrentHashMap.newKeySet();

    var run =
        start(
            admission,
            IntStream.range(0, 3).boxed(),
            item -> {
              started.incrementAndGet();
              try {
                (item == 0 ? releaseFirst : releaseRest).await();
              } catch (InterruptedException e) {
                interrupted.add(item);
                throw e;
              }

              if (item == 2) {
                throw lastFailure;
              }

              finished.incrementAndGet();
              return TaskOutcome.WORKED;
            });

    await()
        .atMost(WAIT)
        .until(() -> started.get() == 3 && run.owner().getState() == Thread.State.WAITING);
    admission.requestStop();
    releaseFirst.countDown();
    await().atMost(WAIT).until(() -> finished.get() == 1);

    assertThatThrownBy(() -> run.result().get(300, MILLISECONDS))
        .as("a stop drains: the run waits for the items still in flight")
        .isInstanceOf(TimeoutException.class);

    releaseRest.countDown();
    var result = run.await();

    System.out.printf(
        "[evidence] stop during the final join: failures %s, interrupted %s, finished %d, failed"
            + " tickets %d%n",
        result.failures(), interrupted, finished.get(), runtime.failedCount());
    assertThat(result.thrown()).isNull();
    assertThat(result.failures()).containsExactly(lastFailure);
    assertThat(interrupted).isEmpty();
    assertThat(finished).hasValue(2);
    assertThat(runtime.failedCount()).isEqualTo(1);
    assertThat(admission.limiter(Workload.SCAN).inflight()).isZero();
    assertThat(runtime.inFlight()).isZero();
    assertThat(released()).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "Should leave an in-flight item's socket open and let its read complete when a stop lands and another item completes")
  void shouldLeaveInFlightSocketOpenAndLetReadCompleteWhenStopLandsAndAnotherItemCompletes()
      throws Exception {
    var admission = scoped();
    var client = new AtomicReference<Socket>();

    try (var server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      var reading = new CountDownLatch(1);
      var releaseSecond = new CountDownLatch(1);
      var secondFinished = new CountDownLatch(1);
      var read = new AtomicReference<String>();

      var run =
          start(
              admission,
              IntStream.range(0, 2).boxed(),
              item -> {
                if (item == 1) {
                  releaseSecond.await();
                  secondFinished.countDown();
                  return TaskOutcome.WORKED;
                }

                var socket = new Socket(server.getInetAddress(), server.getLocalPort());
                client.set(socket);
                reading.countDown();
                try {
                  read.set("read " + socket.getInputStream().read());
                  return TaskOutcome.WORKED;
                } catch (IOException e) {
                  read.set(e.getClass().getSimpleName() + ": " + e.getMessage());
                  throw e;
                }
              });

      try (var peer = server.accept()) {
        assertThat(reading.await(WAIT.toSeconds(), SECONDS)).isTrue();
        await().atMost(WAIT).until(() -> run.owner().getState() == Thread.State.WAITING);
        admission.requestStop();
        releaseSecond.countDown();
        assertThat(secondFinished.await(WAIT.toSeconds(), SECONDS)).isTrue();

        assertThatThrownBy(() -> run.result().get(300, MILLISECONDS))
            .as("the item blocked in its read is still running")
            .isInstanceOf(TimeoutException.class);
        assertThat(client.get().isClosed()).isFalse();

        peer.getOutputStream().write(7);
        peer.getOutputStream().flush();
        var result = run.await();

        System.out.printf(
            "[evidence] stop with an item in a socket read: read %s, socket closed %s, failures"
                + " %s%n",
            read.get(), client.get().isClosed(), result.failures());
        assertThat(read).hasValue("read 7");
        assertThat(client.get().isClosed()).isFalse();
        assertThat(result.failures()).isEmpty();
        assertThat(runtime.failedCount()).isZero();
      }
    } finally {
      if (client.get() != null) {
        client.get().close();
      }
    }
  }

  @Test
  @DisplayName("Should keep a stopped run stopped when another run starts before it pulls again")
  void shouldKeepStoppedRunStoppedWhenAnotherRunStartsBeforeItPullsAgain() throws Exception {
    var admission = scoped();
    var pullOfFifth = new CountDownLatch(1);
    var releaseItems = new CountDownLatch(1);
    var pulled = new AtomicInteger();
    Set<Integer> started = ConcurrentHashMap.newKeySet();
    var items =
        IntStream.range(0, 10)
            .boxed()
            .peek(
                item -> {
                  pulled.incrementAndGet();
                  if (item == 4) {
                    awaitUninterruptibly(pullOfFifth);
                  }
                });

    var run =
        start(
            admission,
            items,
            item -> {
              started.add(item);
              releaseItems.await();
              return TaskOutcome.WORKED;
            });

    await().atMost(WAIT).until(() -> pulled.get() == 5 && runtime.inFlight() == 4);
    admission.requestStop();
    var otherRun = admission.processAll(Workload.REFRESH, Stream.of(1, 2), _ -> TaskOutcome.WORKED);
    pullOfFifth.countDown();
    releaseItems.countDown();
    var result = run.await();

    System.out.printf(
        "[evidence] stop, then another run: started %s, pulled %d, failures %s, other run %s,"
            + " limiter inflight %d, released %s%n",
        started,
        pulled.get(),
        result.failures(),
        otherRun,
        admission.limiter(Workload.SCAN).inflight(),
        released());
    assertThat(otherRun).isEmpty();
    assertThat(started).containsExactlyInAnyOrder(0, 1, 2, 3);
    assertThat(pulled).hasValue(5);
    assertThat(result.failures()).singleElement().isInstanceOf(AdmissionStoppedException.class);
    assertThat(admission.limiter(Workload.SCAN).inflight()).isZero();
    assertThat(runtime.inFlight()).isZero();
    assertThat(released()).isEqualTo(4);
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @ParameterizedTest
  @EnumSource(ForkJoin.class)
  @DisplayName("Should report each failed item in pull order while the other items complete")
  void shouldReportEachFailedItemInPullOrderWhileOtherItemsComplete(ForkJoin forkJoin)
      throws Exception {
    var admission = admission(forkJoin);
    var third = new IllegalStateException("item 3");
    var seventh = new IllegalStateException("item 7");
    var seventhFailed = new CountDownLatch(1);
    var completed = ConcurrentHashMap.<Integer>newKeySet();

    var failures =
        admission.processAll(
            Workload.SCAN,
            IntStream.range(0, 12).boxed(),
            item ->
                switch (item) {
                  case 3 -> {
                    seventhFailed.await(5, SECONDS);
                    throw third;
                  }
                  case 7 -> {
                    seventhFailed.countDown();
                    throw seventh;
                  }
                  default -> {
                    completed.add(item);
                    yield TaskOutcome.WORKED;
                  }
                });

    assertThat(failures).containsExactly(third, seventh);
    assertThat(completed).hasSize(10);
    assertThat(runtime.completed(TaskOutcome.WORKED)).isEqualTo(10);
    assertThat(runtime.failedCount()).isEqualTo(2);
    assertThat(admission.limiter(Workload.SCAN).inflight()).isZero();
    assertThat(calls("success")).isEqualTo(10);
    assertThat(calls("ignored")).isEqualTo(2);
  }

  @ParameterizedTest
  @EnumSource(ForkJoin.class)
  @DisplayName(
      "Should rethrow with the interrupt re-asserted, finish every item thread and leak no permit when the owner is interrupted while waiting for a permit")
  void shouldRethrowReassertFinishItemThreadsAndLeakNoPermitWhenOwnerIsInterruptedWhileWaiting(
      ForkJoin forkJoin) throws Exception {
    var admission = admission(forkJoin);
    var pulled = new AtomicInteger();
    var never = new CountDownLatch(1);
    var itemThreads = ConcurrentHashMap.<Thread>newKeySet();
    var cancelled = new AtomicInteger();

    var run =
        start(
            admission,
            counted(10, pulled),
            _ -> {
              itemThreads.add(Thread.currentThread());
              try {
                never.await();
                return TaskOutcome.WORKED;
              } catch (InterruptedException e) {
                cancelled.incrementAndGet();
                throw e;
              }
            });

    await().atMost(WAIT).until(() -> pulled.get() == 5 && runtime.inFlight() == 4);
    await().atMost(WAIT).until(() -> run.owner().getState() == Thread.State.TIMED_WAITING);
    run.owner().interrupt();
    var result = run.await();

    System.out.printf(
        "[evidence] %s interrupted while waiting: thrown %s, suppressed %d, flag %s, item threads"
            + " %s, cancelled %d, limiter inflight %d%n",
        forkJoin,
        result.thrown(),
        result.thrown() == null ? -1 : result.thrown().getSuppressed().length,
        result.interruptFlag(),
        itemThreads.stream().map(Thread::getState).toList(),
        cancelled.get(),
        admission.limiter(Workload.SCAN).inflight());
    assertThat(result.thrown()).isInstanceOf(InterruptedException.class);
    assertThat(result.thrown().getSuppressed()).isEmpty();
    assertThat(result.interruptFlag()).isTrue();
    assertThat(itemThreads).hasSize(4);
    if (forkJoin == ForkJoin.C1S) {
      // close() returns only after every subtask thread terminated; the executor's close() returns
      // once every task completed, possibly before its thread finished exiting.
      assertThat(itemThreads).noneMatch(Thread::isAlive);
    }

    await()
        .atMost(Duration.ofSeconds(1))
        .until(() -> itemThreads.stream().noneMatch(Thread::isAlive));
    assertThat(cancelled).hasValue(4);
    assertThat(pulled).hasValue(5);
    assertThat(admission.limiter(Workload.SCAN).inflight()).isZero();
    assertThat(runtime.inFlight()).isZero();
    assertThat(runtime.failedCount()).isEqualTo(4);
    assertThat(calls("ignored")).isEqualTo(4);
  }

  @ParameterizedTest
  @EnumSource(ForkJoin.class)
  @DisplayName("Should return the error as a failure without hanging when an item throws an error")
  void shouldReturnErrorAsFailureWithoutHangingWhenItemThrowsError(ForkJoin forkJoin)
      throws Exception {
    var admission = admission(forkJoin);
    var error = new StackOverflowError("simulated");

    var run =
        start(
            admission,
            IntStream.range(0, 20).boxed(),
            item -> {
              if (item == 5) {
                throw error;
              }

              Thread.sleep(2);
              return TaskOutcome.WORKED;
            });
    var result = run.await();

    assertThat(result.thrown()).isNull();
    assertThat(result.failures()).containsExactly(error);
    assertThat(runtime.completed(TaskOutcome.WORKED)).isEqualTo(19);
    assertThat(runtime.failedCount()).isEqualTo(1);
    assertThat(admission.limiter(Workload.SCAN).inflight()).isZero();
    assertThat(runtime.inFlight()).isZero();
  }

  @ParameterizedTest
  @EnumSource(ForkJoin.class)
  @DisplayName("Should hold in-flight at the limit and stop pulling items when items block")
  void shouldHoldInflightAtLimitAndStopPullingItemsWhenItemsBlock(ForkJoin forkJoin)
      throws Exception {
    var admission = admission(forkJoin);
    var pulled = new AtomicInteger();
    var release = new CountDownLatch(1);

    var run =
        start(
            admission,
            counted(10, pulled),
            _ -> {
              release.await(10, SECONDS);
              return TaskOutcome.WORKED;
            });

    await().atMost(WAIT).until(() -> pulled.get() == 5 && runtime.inFlight() == 4);
    assertThat(runtime.admissionLimit()).isEqualTo(4.0);
    release.countDown();
    var result = run.await();

    assertThat(result.failures()).isEmpty();
    assertThat(pulled).hasValue(10);
    assertThat(runtime.completed(TaskOutcome.WORKED)).isEqualTo(10);
    assertThat(calls("success")).isEqualTo(10);
    assertThat(admission.limiter(Workload.SCAN).inflight()).isZero();
  }

  @ParameterizedTest
  @EnumSource(ForkJoin.class)
  @DisplayName("Should propagate the walk's own failure after admitted items finish")
  void shouldPropagateWalksOwnFailureAfterAdmittedItemsFinish(ForkJoin forkJoin) {
    var admission = admission(forkJoin);
    var walkFailure = new UncheckedIOException(new IOException("walk"));
    var finished = new AtomicInteger();
    var items =
        Stream.of(1, 2, 3)
            .peek(
                item -> {
                  if (item == 3) {
                    throw walkFailure;
                  }
                });

    var result =
        catchThrowable(
            () ->
                admission.processAll(
                    Workload.SCAN,
                    items,
                    _ -> {
                      Thread.sleep(50);
                      finished.incrementAndGet();
                      return TaskOutcome.WORKED;
                    }));

    assertThat(result).isSameAs(walkFailure);
    assertThat(result.getSuppressed()).isEmpty();
    assertThat(finished).hasValue(2);
    assertThat(runtime.inFlight()).isZero();
    assertThat(admission.limiter(Workload.SCAN).inflight()).isZero();
  }

  @ParameterizedTest
  @EnumSource(ForkJoin.class)
  @DisplayName("Should fail the run after admitted items finish when no permit frees in time")
  void shouldFailRunAfterAdmittedItemsFinishWhenNoPermitFreesInTime(ForkJoin forkJoin)
      throws Exception {
    var admission = forkJoin.create(runtime, registry, Duration.ofMillis(20));
    var pulled = new AtomicInteger();
    var finished = new AtomicInteger();

    var failures =
        admission.processAll(
            Workload.SCAN,
            counted(10, pulled),
            _ -> {
              new CountDownLatch(1).await(300, MILLISECONDS);
              finished.incrementAndGet();
              return TaskOutcome.WORKED;
            });

    assertThat(failures).singleElement().isInstanceOf(AdaptiveAdmissionTimeoutException.class);
    assertThat(pulled).hasValue(5);
    assertThat(finished).hasValue(4);
    assertThat(admission.limiter(Workload.SCAN).inflight()).isZero();
  }

  @Test
  @DisplayName(
      "Should never leak a permit or interrupt an item when stops land at random moments across many runs")
  void shouldNeverLeakPermitOrInterruptItemWhenStopsLandAtRandomMomentsAcrossManyRuns()
      throws Exception {
    var admission = scoped();
    var stoppedRuns = 0;
    var interrupted = new AtomicInteger();
    var runs = 400;

    for (var round = 0; round < runs; round++) {
      var started = new AtomicInteger();
      var stopAfter = ThreadLocalRandom.current().nextInt(1, 60);
      var run =
          start(
              admission,
              IntStream.range(0, 60).boxed(),
              _ -> {
                if (started.incrementAndGet() == stopAfter) {
                  admission.requestStop();
                }

                try {
                  Thread.sleep(0, ThreadLocalRandom.current().nextInt(1, 300_000));
                } catch (InterruptedException e) {
                  interrupted.incrementAndGet();
                  throw e;
                }

                return TaskOutcome.WORKED;
              });
      var result = run.await();

      assertThat(result.thrown()).isNull();
      assertThat(result.failures())
          .as("round %d", round)
          .allMatch(AdmissionStoppedException.class::isInstance);
      assertThat(admission.limiter(Workload.SCAN).inflight()).as("round %d", round).isZero();
      assertThat(runtime.inFlight()).as("round %d", round).isZero();
      if (!result.failures().isEmpty()) {
        stoppedRuns++;
      }
    }

    System.out.printf(
        "[evidence] random stops: %d of %d runs reported a stop, interrupted items %d, limiter"
            + " inflight %d, released %s, failed tickets %d%n",
        stoppedRuns,
        runs,
        interrupted.get(),
        admission.limiter(Workload.SCAN).inflight(),
        released(),
        runtime.failedCount());
    assertThat(stoppedRuns).isPositive();
    assertThat(interrupted).hasValue(0);
    assertThat(runtime.failedCount()).isZero();
  }

  @Test
  @DisplayName(
      "Should never start a subtask forked after the scope was cancelled (the JDK behavior the permit sweep covers)")
  void shouldNeverStartSubtaskForkedAfterScopeWasCancelled() throws Exception {
    var ran = new AtomicBoolean();
    Set<Subtask.State> lateStates = ConcurrentHashMap.newKeySet();

    try (var scope = StructuredTaskScope.open(Joiner.<String>allUntil(_ -> true))) {
      scope.fork(() -> "first");
      await().atMost(WAIT).until(scope::isCancelled);
      var late =
          scope.fork(
              () -> {
                ran.set(true);
                return "late";
              });
      scope.join();
      lateStates.add(late.state());
    }

    assertThat(ran).isFalse();
    assertThat(lateStates).containsExactly(Subtask.State.UNAVAILABLE);
  }
}
