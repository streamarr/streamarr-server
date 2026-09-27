package com.streamarr.server.services.library.admission.adaptive;

import com.streamarr.server.services.library.admission.AdmissionRuntime;
import com.streamarr.server.services.library.admission.AdmittedTask;
import com.streamarr.server.services.library.admission.TaskOutcome;
import com.streamarr.server.services.library.admission.Workload;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Variant C1S: C1's limiter, permit releases and accounting, with each run's items forked into one
 * {@link StructuredTaskScope} instead of a virtual-thread-per-task executor.
 *
 * <p>The scope's joiner is {@code allUntil(_ -> false)}: only {@code close()} cancels the scope, so
 * every admitted item runs to its end, and the failures are read from the FAILED subtasks in pull
 * order, exactly the list C1 reads from its futures.
 *
 * <p>A stop drains. {@link #requestStop()} ends admission before the next pull or right after the
 * next granted permit. The items already running are not interrupted: the run returns once they
 * end, with their own failures followed by {@link AdmissionStoppedException} when the stop kept an
 * item from running. Cancelling the scope on a stop would interrupt every running item instead: on
 * JDK 27 an interrupted virtual thread closes the {@code java.net.Socket} it is reading (a pgjdbc
 * connection mid-statement), {@code HttpClient.send} cancels its exchange, and a subtask that ends
 * after the cancellation stays UNAVAILABLE, so its failure is lost.
 *
 * <p>Interrupting the owner thread (shutdown) is the one hard cancel: the permit wait or the join
 * throws, and {@code close()} cancels the scope, interrupting every unfinished item with exactly
 * those effects. C1's executor {@code close()} does the same through {@code shutdownNow()}.
 *
 * <p>Every permit is released exactly once. A subtask that ran releases its own permit, as in C1. A
 * permit granted after the stop is released at once. After {@code close()}, when no subtask thread
 * can still be running, the run releases as ignored the permit of any admitted item whose subtask
 * never started.
 */
public class ScopedAdaptiveFileAdmission extends AdaptiveFileAdmission {

  private final Set<AtomicBoolean> runsInProgress = ConcurrentHashMap.newKeySet();

  public ScopedAdaptiveFileAdmission(
      AdaptiveLimitAlgorithm algorithm,
      AdmissionRuntime runtime,
      Duration acquireTimeout,
      MeterRegistry registry) {
    super(algorithm, runtime, acquireTimeout, registry);
  }

  @Override
  public String name() {
    return super.name() + "-scope";
  }

  /**
   * Throwaway harness hook: stops every run in progress. A run that starts later is not stopped.
   */
  public void requestStop() {
    runsInProgress.forEach(stop -> stop.set(true));
  }

  @Override
  public <T> List<Throwable> processAll(Workload workload, Stream<T> items, AdmittedTask<T> task)
      throws InterruptedException {
    var stop = new AtomicBoolean();
    runsInProgress.add(stop);
    var run = startRun(workload, stop::get);
    var admitted = new ArrayList<Admitted>();
    Optional<Throwable> admissionFailure;
    List<Subtask<TaskOutcome>> subtasks;

    try (var scope = StructuredTaskScope.open(Joiner.<TaskOutcome>allUntil(_ -> false))) {
      admissionFailure = admitInto(scope, run, items, task, admitted);
      subtasks = join(scope);
    } finally {
      runsInProgress.remove(stop);
      admitted.forEach(Admitted::abandon);
      logFinished(run, admitted.size());
    }

    // Read after close(): no subtask thread is left, so no subtask state can still change.
    var failures = failedInPullOrder(subtasks);
    admissionFailure.ifPresent(failures::add);
    return failures;
  }

  private <T> Optional<Throwable> admitInto(
      StructuredTaskScope<TaskOutcome, List<Subtask<TaskOutcome>>, RuntimeException> scope,
      AdmissionRun run,
      Stream<T> items,
      AdmittedTask<T> task,
      List<Admitted> admitted)
      throws InterruptedException {
    try {
      return admitAll(
          run,
          items,
          task,
          admittedItem -> {
            admitted.add(admittedItem);
            scope.fork(admittedItem.work());
          });
    } catch (InterruptedException | RuntimeException | Error abort) {
      joinAfterAbort(scope);
      throw abort;
    }
  }

  /**
   * The owner must join once before closing, or {@code close()} throws. After an interrupt the join
   * returns at once (the interrupt stays set and {@code close()} cancels the admitted items); after
   * a failed walk it waits for the admitted items, as C1's executor close does.
   */
  private static void joinAfterAbort(
      StructuredTaskScope<TaskOutcome, List<Subtask<TaskOutcome>>, RuntimeException> scope) {
    try {
      scope.join();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  private static List<Subtask<TaskOutcome>> join(
      StructuredTaskScope<TaskOutcome, List<Subtask<TaskOutcome>>, RuntimeException> scope)
      throws InterruptedException {
    try {
      return scope.join();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw interrupted;
    }
  }

  private static List<Throwable> failedInPullOrder(List<Subtask<TaskOutcome>> subtasks) {
    return subtasks.stream()
        .filter(subtask -> subtask.state() == Subtask.State.FAILED)
        .map(Subtask::exception)
        .collect(Collectors.toCollection(ArrayList::new));
  }
}
