package com.streamarr.server.services.library.admission.adaptive;

import com.netflix.concurrency.limits.Limiter;
import com.streamarr.server.services.library.admission.AdmissionFutures;
import com.streamarr.server.services.library.admission.AdmissionRuntime;
import com.streamarr.server.services.library.admission.AdmissionTicket;
import com.streamarr.server.services.library.admission.AdmittedTask;
import com.streamarr.server.services.library.admission.FileAdmission;
import com.streamarr.server.services.library.admission.TaskOutcome;
import com.streamarr.server.services.library.admission.Workload;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * Variants C1 and C2: a delay-based concurrency limit decides how many items run at once.
 *
 * <p>The walking thread pulls the next item, then blocks for a permit before it forks the item's
 * virtual thread, so a full limit stops the directory walk itself. Each task releases its permit
 * exactly once when it ends:
 *
 * <ul>
 *   <li>{@code onDropped} when a TMDB request was retried after a 429, a TMDB request timed out, or
 *       a database connection request timed out during the task, whether or not the task caught it;
 *   <li>otherwise {@code onSuccess} when the task reached remote identification ({@link
 *       TaskOutcome#WORKED}), which gives the limit a round-trip sample;
 *   <li>otherwise {@code onIgnore}: a short-circuited item takes a few milliseconds of database
 *       work and would drag the limit's baseline round trip down, and any other failure carries no
 *       meaningful round trip.
 * </ul>
 *
 * <p>A permit that is not granted within the acquire timeout, or an interrupt while waiting, stops
 * admitting: the admitted items finish, then the timeout is returned as one more failure (the scan
 * or refresh fails) or the interrupt is rethrown.
 */
@Slf4j
public class AdaptiveFileAdmission implements FileAdmission {

  private final AdaptiveLimitAlgorithm algorithm;
  private final AdmissionRuntime runtime;
  private final Map<Workload, WorkloadLimiter> limiters = new EnumMap<>(Workload.class);
  private volatile WorkloadLimiter lastStarted;

  public AdaptiveFileAdmission(
      AdaptiveLimitAlgorithm algorithm,
      AdmissionRuntime runtime,
      Duration acquireTimeout,
      MeterRegistry registry) {
    this(algorithm, runtime, acquireTimeout, registry, false);
  }

  /**
   * @param queueingRtt feed the limit {@link QueueingRttLimit}'s queueing round trip instead of the
   *     whole-task round trip
   */
  public AdaptiveFileAdmission(
      AdaptiveLimitAlgorithm algorithm,
      AdmissionRuntime runtime,
      Duration acquireTimeout,
      MeterRegistry registry,
      boolean queueingRtt) {
    this.algorithm = algorithm;
    this.runtime = runtime;

    for (var workload : Workload.values()) {
      limiters.put(
          workload,
          new WorkloadLimiter(algorithm, workload, acquireTimeout, registry, queueingRtt));
    }

    lastStarted = limiters.get(Workload.SCAN);
    runtime.setAdmissionLimit(() -> lastStarted.limit());
  }

  @Override
  public String name() {
    return algorithm.admissionName();
  }

  @Override
  public <T> List<Throwable> processAll(Workload workload, Stream<T> items, AdmittedTask<T> task)
      throws InterruptedException {
    var run = startRun(workload, () -> false);
    var tasks = new ArrayList<Future<TaskOutcome>>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var admissionFailure =
          admitAll(run, items, task, admitted -> tasks.add(executor.submit(admitted.work())));
      var failures = new ArrayList<>(AdmissionFutures.awaitInOrder(tasks));
      admissionFailure.ifPresent(failures::add);
      return failures;
    } finally {
      logFinished(run, tasks.size());
    }
  }

  /**
   * Starts one scan or refresh on its workload's limiter. {@code stop} is polled by {@link
   * #admitAll} before each item is pulled and after each granted permit.
   */
  final AdmissionRun startRun(Workload workload, BooleanSupplier stop) {
    var limiter = limiters.get(workload);
    lastStarted = limiter;
    limiter.resetTrace();
    return new AdmissionRun(workload, limiter, new ReleaseTally(), stop);
  }

  final void logFinished(AdmissionRun run, int admitted) {
    log.info(
        "POC {} admission of {} finished: {} admitted, {}; {}",
        name(),
        run.workload(),
        admitted,
        run.tally(),
        run.limiter().trace());
  }

  /** The limiter behind {@code workload}; kept for the life of the bean. */
  final WorkloadLimiter limiter(Workload workload) {
    return limiters.get(workload);
  }

  /**
   * Pulls each item, blocks for its permit, then hands the admitted item to {@code fork}. Returns
   * the admission failure to append after the items' own failures: the acquire timeout, or {@link
   * AdmissionStoppedException} when {@code run}'s stop kept an item from running. A stop seen only
   * after the last item was admitted is not reported: every item ran.
   */
  final <T> Optional<Throwable> admitAll(
      AdmissionRun run, Stream<T> items, AdmittedTask<T> task, Fork fork)
      throws InterruptedException {
    var iterator = items.iterator();

    while (iterator.hasNext()) {
      if (run.stopRequested()) {
        return stopped(run);
      }

      var item = iterator.next();
      var permit = run.limiter().acquire();

      if (permit.isEmpty()) {
        return Optional.of(stoppedAdmitting(run.workload(), run.limiter()));
      }

      var release = new PermitRelease(permit.get(), run.tally());

      if (run.stopRequested()) {
        release.release(Release.IGNORED);
        return stopped(run);
      }

      forkAdmitted(fork, release, task, item);
    }

    return Optional.empty();
  }

  private static Optional<Throwable> stopped(AdmissionRun run) {
    return Optional.of(new AdmissionStoppedException(run.workload()));
  }

  private static Throwable stoppedAdmitting(Workload workload, WorkloadLimiter limiter)
      throws InterruptedException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedException("Interrupted while waiting for an admission permit");
    }

    return new AdaptiveAdmissionTimeoutException(workload, limiter.acquireTimeout());
  }

  private <T> void forkAdmitted(Fork fork, PermitRelease release, AdmittedTask<T> task, T item) {
    var ticket = runtime.admit();
    var admitted = new Admitted(ticket, release, () -> runAndRelease(ticket, release, task, item));

    try {
      fork.fork(admitted);
    } catch (RuntimeException rejected) {
      admitted.abandon();
      throw rejected;
    }
  }

  /** Starts an admitted item's work: the executor for C1, the structured task scope for C1S. */
  @FunctionalInterface
  interface Fork {
    void fork(Admitted admitted);
  }

  /** One scan or refresh: its workload's limiter, its release counts and its stop request. */
  record AdmissionRun(
      Workload workload, WorkloadLimiter limiter, ReleaseTally tally, BooleanSupplier stop) {

    boolean stopRequested() {
      return stop.getAsBoolean();
    }
  }

  /** An item holding a permit and a ticket, and the work that releases both when it runs. */
  record Admitted(AdmissionTicket ticket, PermitRelease release, Callable<TaskOutcome> work) {

    /**
     * Ends an item whose work never ran: fails its ticket and releases its permit as ignored. A
     * no-op for an item whose work ran, because both ends are exactly-once.
     */
    void abandon() {
      ticket.fail();
      release.release(Release.IGNORED);
    }
  }

  private static <T> TaskOutcome runAndRelease(
      AdmissionTicket ticket, PermitRelease release, AdmittedTask<T> task, T item)
      throws Exception {
    var dropped = new AtomicBoolean();
    var poolWait = new LongAdder();
    var signal = Release.IGNORED;

    try {
      var outcome =
          ScopedValue.where(AdaptiveDropSignal.DROPPED, dropped)
              .where(QueueingDelay.POOL_WAIT, poolWait)
              .call(() -> ticket.run(task, item));
      signal = Release.forOutcome(outcome, dropped.get() || ticket.tmdbRetried());
      return outcome;
    } catch (Throwable failure) {
      var droppedByFailure = AdaptiveDropSignal.isDropCause(failure);
      signal = Release.forFailure(dropped.get() || ticket.tmdbRetried() || droppedByFailure);
      throw failure;
    } finally {
      release.release(signal, ticket.tmdbGateWaitNanos() + poolWait.sum());
    }
  }

  /** Which listener call releases a permit. */
  enum Release {
    SUCCESS,
    IGNORED,
    DROPPED;

    static Release forOutcome(TaskOutcome outcome, boolean dropped) {
      if (dropped) {
        return DROPPED;
      }

      return switch (outcome) {
        case WORKED -> SUCCESS;
        case SHORT_CIRCUITED -> IGNORED;
      };
    }

    static Release forFailure(boolean dropped) {
      return dropped ? DROPPED : IGNORED;
    }
  }

  /** Releases one permit exactly once, however often {@link #release} is reached. */
  static final class PermitRelease {

    private final Limiter.Listener permit;
    private final ReleaseTally tally;
    private final AtomicBoolean released = new AtomicBoolean();

    private PermitRelease(Limiter.Listener permit, ReleaseTally tally) {
      this.permit = permit;
      this.tally = tally;
    }

    void release(Release signal) {
      release(signal, 0);
    }

    /** {@code queueingNanos}: the task's queueing time, for a {@link QueueingRttLimit}. */
    void release(Release signal, long queueingNanos) {
      if (!released.compareAndSet(false, true)) {
        return;
      }

      tally.count(signal);
      try {
        ScopedValue.where(QueueingDelay.SAMPLE_WAIT, queueingNanos)
            .run(
                () -> {
                  switch (signal) {
                    case SUCCESS -> permit.onSuccess();
                    case IGNORED -> permit.onIgnore();
                    case DROPPED -> permit.onDropped();
                  }
                });
      } catch (RuntimeException unexpected) {
        // PositiveRttLimit removes the known throw; anything else must not mask the task's result.
        log.error("Releasing an admission permit failed", unexpected);
      }
    }
  }

  /** Per-run release counts for the run summary. */
  static final class ReleaseTally {

    private final Map<Release, LongAdder> counts = new EnumMap<>(Release.class);

    private ReleaseTally() {
      for (var release : Release.values()) {
        counts.put(release, new LongAdder());
      }
    }

    void count(Release release) {
      counts.get(release).increment();
    }

    @Override
    public String toString() {
      return "success "
          + counts.get(Release.SUCCESS).sum()
          + ", ignored "
          + counts.get(Release.IGNORED).sum()
          + ", dropped "
          + counts.get(Release.DROPPED).sum();
    }
  }
}
