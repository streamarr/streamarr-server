package com.streamarr.server.services.concurrency;

import com.netflix.concurrency.limits.Limit;
import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.limit.Gradient2Limit;
import com.netflix.concurrency.limits.limiter.BlockingLimiter;
import com.netflix.concurrency.limits.limiter.SimpleLimiter;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import lombok.Builder;
import lombok.NonNull;
import org.apache.commons.lang3.exception.ExceptionUtils;

/**
 * Admits the tasks of one library operation through a concurrency limit that adjusts to their
 * latency (ADR 0041). The walking thread acquires an admission before it pulls the next subject, so
 * the walk never runs ahead of admission, and each admitted task reports exactly one outcome, which
 * returns its admission.
 */
public final class AdaptiveAdmission implements AutoCloseable {

  // BlockingLimiter refuses a wait of an hour or more. A wait that times out simply waits again.
  private static final Duration ADMISSION_WAIT = Duration.ofMinutes(59);

  private static final List<Class<? extends Throwable>> TIMEOUTS =
      List.of(
          SQLTransientConnectionException.class,
          HttpTimeoutException.class,
          SocketTimeoutException.class,
          TimeoutException.class);

  private final SimpleLimiter<Void> limiter;
  private final Limiter<Void> blockingLimiter;
  private final AdmissionListener listener;
  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * @param limit the limit that decides how many tasks run at once; {@link #learnedLimit()} in
   *     production
   * @param nanoClock the clock that measures each task's latency
   * @param listener receives admissions and reports; none when unset
   */
  @Builder
  public AdaptiveAdmission(
      @NonNull Limit limit, @NonNull LongSupplier nanoClock, AdmissionListener listener) {
    // No limit-change consumer is registered: SimpleLimiter releases its permit only after the
    // sample, so a consumer that threw would leak the permit.
    this.limiter = SimpleLimiter.newBuilder().limit(limit).nanoClock(nanoClock).build();
    this.blockingLimiter = BlockingLimiter.wrap(limiter, ADMISSION_WAIT);
    this.listener = Objects.requireNonNullElse(listener, AdmissionListener.NONE);
  }

  /**
   * Returns a fresh Gradient2 limit with the tuning that ADR 0041 keeps in code. It starts high
   * enough that work which reports only ignored tasks, such as an unchanged rescan, runs as fast as
   * it did without admission.
   */
  public static Limit learnedLimit() {
    return Gradient2Limit.newBuilder()
        .initialLimit(16)
        .minLimit(1)
        .maxConcurrency(200)
        .queueSize(1)
        .rttTolerance(1.0)
        .build();
  }

  /**
   * Admits a task for each subject in turn and forks it on a thread from {@code threads}, then
   * joins the tasks in the order they were admitted. A task that throws ends admission: no further
   * subject is pulled, and the tasks already admitted finish. One task's failure cancels no other
   * task.
   *
   * @return the failures of the tasks that threw, in admission order
   * @throws InterruptedException if the calling thread is interrupted; the admitted tasks are
   *     interrupted and joined first
   */
  public <S> List<Throwable> forkAdmittedTasks(
      @NonNull Iterator<S> subjects, @NonNull AdmittedTask<S> task, @NonNull ThreadFactory threads)
      throws InterruptedException {
    try (var executor = Executors.newThreadPerTaskExecutor(threads)) {
      var loop = new AdmissionLoop<>(subjects, task, executor);
      try {
        loop.forkWhileAdmitted();
        return loop.joinInAdmissionOrder();
      } catch (InterruptedException interrupted) {
        executor.shutdownNow();
        throw interrupted;
      }
    }
  }

  // BlockingLimiter grants a free admission without checking the interrupt. An interrupted or
  // timed-out wait returns nothing, with the interrupt restored, so each pass checks it first.
  private Admission acquire() throws InterruptedException {
    while (true) {
      throwIfInterrupted();
      var granted = blockingLimiter.acquire(null);
      if (granted.isPresent()) {
        return new Admission(granted.get());
      }
    }
  }

  private static void throwIfInterrupted() throws InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException("Interrupted while admitting tasks");
    }
  }

  private static AdmittedTaskReport failureReport(Throwable failure, AtomicBoolean retried) {
    if (retried.get() || hasTimeoutCause(failure)) {
      return AdmittedTaskReport.DROP;
    }

    return AdmittedTaskReport.IGNORED;
  }

  private static boolean hasTimeoutCause(Throwable failure) {
    return ExceptionUtils.getThrowableList(failure).stream()
        .anyMatch(cause -> TIMEOUTS.stream().anyMatch(timeout -> timeout.isInstance(cause)));
  }

  private void report(Admission admission, AdmittedTaskReport report) {
    admission.complete(report);
    listener.onReported(report);
  }

  /** Returns the current limit. */
  public int limit() {
    return limiter.getLimit();
  }

  /** Returns how many admitted tasks have not reported yet. */
  public int inFlight() {
    return limiter.getInflight();
  }

  /** Tells the listener, once, that the operation is over. */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      listener.onClosed();
    }
  }

  /** The subjects, task and forked tasks of one call to {@link #forkAdmittedTasks}. */
  private final class AdmissionLoop<S> {

    private final Iterator<S> subjects;
    private final AdmittedTask<S> task;
    private final ExecutorService executor;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final List<Future<?>> forked = new ArrayList<>();

    private AdmissionLoop(Iterator<S> subjects, AdmittedTask<S> task, ExecutorService executor) {
      this.subjects = subjects;
      this.task = task;
      this.executor = executor;
    }

    private void forkWhileAdmitted() throws InterruptedException {
      while (!ended.get()) {
        var admission = acquire();
        var subject = nextSubject(admission);
        if (subject.isEmpty()) {
          return;
        }

        forked.add(fork(admission, subject.get()));
        listener.onAdmitted();
      }
    }

    // Returns the admission unused when the executor cannot start the task's thread.
    private Future<?> fork(Admission admission, S subject) {
      try {
        return executor.submit(() -> runAdmitted(admission, subject));
      } catch (RuntimeException notForked) {
        admission.returnUnused();
        throw notForked;
      }
    }

    // Returns the admission unused when admission ended or no subject follows.
    private Optional<S> nextSubject(Admission admission) {
      try {
        if (ended.get() || !subjects.hasNext()) {
          admission.returnUnused();
          return Optional.empty();
        }

        return Optional.of(subjects.next());
      } catch (RuntimeException unreadable) {
        admission.returnUnused();
        throw unreadable;
      }
    }

    private Void runAdmitted(Admission admission, S subject) throws Exception {
      var retried = new AtomicBoolean();
      AdmittedTaskReport report;
      try {
        report =
            ScopedValue.where(AdmittedTaskRetries.RETRIED, retried).call(() -> task.run(subject));
      } catch (Exception | Error failure) {
        // An Error returns its admission too, or the walker could wait for it forever. Admission
        // ends before the admission returns, so the walker that the return wakes stops.
        ended.set(true);
        report(admission, failureReport(failure, retried));
        throw failure;
      }

      if (retried.get()) {
        report = AdmittedTaskReport.DROP;
      }

      report(admission, report);
      return null;
    }

    private List<Throwable> joinInAdmissionOrder() throws InterruptedException {
      var failures = new ArrayList<Throwable>();
      for (var forkedTask : forked) {
        // Future.get returns a finished task's result without checking the interrupt.
        throwIfInterrupted();
        try {
          forkedTask.get();
        } catch (ExecutionException failure) {
          failures.add(failure.getCause());
        }
      }

      return List.copyOf(failures);
    }
  }

  /** One granted admission, returned exactly once. */
  private static final class Admission {

    private final Limiter.Listener granted;
    private final AtomicBoolean returned = new AtomicBoolean();

    private Admission(Limiter.Listener granted) {
      this.granted = granted;
    }

    void returnUnused() {
      complete(AdmittedTaskReport.IGNORED);
    }

    void complete(AdmittedTaskReport report) {
      if (!returned.compareAndSet(false, true)) {
        throw new IllegalStateException("An admission was returned twice");
      }

      switch (report) {
        case LATENCY -> granted.onSuccess();
        case IGNORED -> granted.onIgnore();
        case DROP -> granted.onDropped();
      }
    }
  }
}
