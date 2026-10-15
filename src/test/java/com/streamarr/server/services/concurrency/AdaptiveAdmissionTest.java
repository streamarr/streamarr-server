package com.streamarr.server.services.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.netflix.concurrency.limits.Limit;
import com.netflix.concurrency.limits.limit.FixedLimit;
import com.streamarr.server.support.BoundedTask;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Adaptive admission")
class AdaptiveAdmissionTest {

  private static final Duration BOUND = Duration.ofSeconds(5);
  private static final ThreadFactory THREADS = Thread.ofVirtual().name("admitted-", 0).factory();
  private static final int INITIAL_LIMIT = 16;

  private final TaskLatencyClock clock = new TaskLatencyClock();
  private final CapturingListener listener = new CapturingListener();

  @Test
  @DisplayName("Should raise the limit while task latency holds steady")
  void shouldRaiseTheLimitWhileTaskLatencyHoldsSteady() throws Exception {
    var admission = learnedAdmission();
    var tasks = new HeldTasks(_ -> AdmittedTaskReport.LATENCY);
    var subjects = new EndlessSubjects();

    try (var walk = startWalk(admission, subjects, tasks)) {
      clock.taskLatency(Duration.ofMillis(10));
      releaseInAdmissionOrder(admission, tasks, 30);

      assertThat(admission.limit()).isGreaterThan(INITIAL_LIMIT);

      finish(walk, subjects, tasks);
    }
  }

  @Test
  @DisplayName("Should lower the limit when task latency rises")
  void shouldLowerTheLimitWhenTaskLatencyRises() throws Exception {
    var admission = learnedAdmission();
    var tasks = new HeldTasks(_ -> AdmittedTaskReport.LATENCY);
    var subjects = new EndlessSubjects();

    try (var walk = startWalk(admission, subjects, tasks)) {
      clock.taskLatency(Duration.ofMillis(10));
      releaseInAdmissionOrder(admission, tasks, 30);
      var steadyLimit = admission.limit();

      clock.taskLatency(Duration.ofMillis(30));
      releaseInAdmissionOrder(admission, tasks, 10);

      assertThat(admission.limit()).isLessThan(INITIAL_LIMIT).isLessThan(steadyLimit);

      finish(walk, subjects, tasks);
    }
  }

  @Test
  @DisplayName("Should lower the limit when slow retried tasks report drops")
  void shouldLowerTheLimitWhenSlowRetriedTasksReportDrops() throws Exception {
    var admission = learnedAdmission();
    var retrying = new AtomicBoolean();
    var tasks =
        new HeldTasks(
            _ -> {
              if (retrying.get()) {
                AdmittedTaskRetries.markRetried();
              }

              return AdmittedTaskReport.LATENCY;
            });
    var subjects = new EndlessSubjects();

    try (var walk = startWalk(admission, subjects, tasks)) {
      clock.taskLatency(Duration.ofMillis(10));
      releaseInAdmissionOrder(admission, tasks, 30);

      retrying.set(true);
      clock.taskLatency(Duration.ofMillis(30));
      releaseInAdmissionOrder(admission, tasks, 10);

      assertThat(admission.limit()).isLessThan(INITIAL_LIMIT);
      assertThat(listener.reportsOf(AdmittedTaskReport.DROP)).isEqualTo(10);

      finish(walk, subjects, tasks);
    }
  }

  @Test
  @DisplayName("Should keep the limit when tasks report that they were ignored")
  void shouldKeepTheLimitWhenTasksReportThatTheyWereIgnored() throws Exception {
    var admission = learnedAdmission();
    var tasks = new HeldTasks(_ -> AdmittedTaskReport.IGNORED);
    var subjects = new EndlessSubjects();

    try (var walk = startWalk(admission, subjects, tasks)) {
      clock.taskLatency(Duration.ofMillis(10));
      releaseInAdmissionOrder(admission, tasks, 20);
      clock.taskLatency(Duration.ofMillis(30));
      releaseInAdmissionOrder(admission, tasks, 20);

      assertThat(admission.limit()).isEqualTo(INITIAL_LIMIT);
      assertThat(listener.reportsOf(AdmittedTaskReport.IGNORED)).isEqualTo(40);

      finish(walk, subjects, tasks);
    }
  }

  @Test
  @DisplayName("Should grant no admission while tasks in flight reach the limit")
  void shouldGrantNoAdmissionWhileTasksInFlightReachTheLimit() throws Exception {
    var admission = admissionWith(FixedLimit.of(2));
    var tasks = new HeldTasks(_ -> AdmittedTaskReport.LATENCY);
    var subjects = new CountingSubjects(3);

    try (var walk = startWalk(admission, subjects, tasks)) {
      tasks.awaitStarted(2);

      assertThat(subjects.pulled()).isEqualTo(2);
      assertThat(admission.inFlight()).isEqualTo(2);

      tasks.releaseOldest();
      tasks.awaitStarted(1);
      assertThat(subjects.pulled()).isEqualTo(3);

      tasks.releaseAll();
      assertThat(walk.await(BOUND)).isEmpty();
    }
  }

  @Test
  @DisplayName("Should pull the next subject only after an admission is granted")
  void shouldPullTheNextSubjectOnlyAfterAnAdmissionIsGranted() throws Exception {
    var admission = admissionWith(FixedLimit.of(1));
    var subjects = new CountingSubjects(3, admission);

    var failures = admission.forkAdmittedTasks(subjects, _ -> AdmittedTaskReport.LATENCY, THREADS);

    assertThat(failures).isEmpty();
    assertThat(subjects.inFlightAtEachPull()).containsExactly(1, 1, 1);
    assertThat(admission.inFlight()).isZero();
  }

  @Test
  @DisplayName(
      "Should return every admission exactly once when tasks report latency, ignore, drop and throw")
  void shouldReturnEveryAdmissionExactlyOnceWhenTasksReportLatencyIgnoreDropAndThrow()
      throws Exception {
    var admission = admissionWith(FixedLimit.of(1));
    var failure = new IllegalStateException("task failed");
    AdmittedTask<Integer> task =
        subject ->
            switch (subject) {
              case 0 -> AdmittedTaskReport.LATENCY;
              case 1 -> AdmittedTaskReport.IGNORED;
              case 2 -> AdmittedTaskReport.DROP;
              default -> throw failure;
            };

    var failures = admission.forkAdmittedTasks(new CountingSubjects(4), task, THREADS);

    assertThat(failures).containsExactly(failure);
    assertThat(admission.inFlight()).isZero();
    assertThat(listener.admitted()).isEqualTo(4);
    assertThat(listener.reports())
        .containsExactly(
            AdmittedTaskReport.LATENCY,
            AdmittedTaskReport.IGNORED,
            AdmittedTaskReport.DROP,
            AdmittedTaskReport.IGNORED);
  }

  @Test
  @DisplayName("Should return the admission as ignored when no subject follows the grant")
  void shouldReturnTheAdmissionAsIgnoredWhenNoSubjectFollowsTheGrant() throws Exception {
    var admission = admissionWith(FixedLimit.of(1));

    var failures =
        admission.forkAdmittedTasks(
            new CountingSubjects(0), _ -> AdmittedTaskReport.LATENCY, THREADS);

    assertThat(failures).isEmpty();
    assertThat(admission.inFlight()).isZero();
    assertThat(listener.admitted()).isZero();
    assertThat(listener.reports()).isEmpty();
  }

  @Test
  @DisplayName("Should return the admission and rethrow when the subjects cannot be read")
  void shouldReturnTheAdmissionAndRethrowWhenTheSubjectsCannotBeRead() {
    var admission = admissionWith(FixedLimit.of(1));
    var unreadable = new UncheckedIOException(new IOException("directory vanished"));
    Iterator<Integer> subjects =
        new Iterator<>() {
          @Override
          public boolean hasNext() {
            throw unreadable;
          }

          @Override
          public Integer next() {
            throw new NoSuchElementException();
          }
        };

    assertThatThrownBy(
            () -> admission.forkAdmittedTasks(subjects, _ -> AdmittedTaskReport.LATENCY, THREADS))
        .isSameAs(unreadable);
    assertThat(admission.inFlight()).isZero();
  }

  @Test
  @DisplayName("Should admit no further task and let admitted tasks finish when a task throws")
  void shouldAdmitNoFurtherTaskAndLetAdmittedTasksFinishWhenATaskThrows() throws Exception {
    var admission = admissionWith(FixedLimit.of(2));
    var failure = new IllegalStateException("first task failed");
    var heldStarted = new CountDownLatch(1);
    var releaseHeld = new CountDownLatch(1);
    var heldFinished = new AtomicBoolean();
    var subjects = new CountingSubjects(3);
    AdmittedTask<Integer> task =
        subject -> {
          if (subject == 0) {
            assertThat(heldStarted.await(BOUND.toNanos(), TimeUnit.NANOSECONDS)).isTrue();
            throw failure;
          }

          heldStarted.countDown();
          assertThat(releaseHeld.await(BOUND.toNanos(), TimeUnit.NANOSECONDS)).isTrue();
          heldFinished.set(true);
          return AdmittedTaskReport.LATENCY;
        };

    try (var walk = BoundedTask.start(() -> admission.forkAdmittedTasks(subjects, task, THREADS))) {
      listener.awaitReport();
      assertThat(heldFinished).isFalse();

      releaseHeld.countDown();

      assertThat(walk.await(BOUND)).containsExactly(failure);
    }

    assertThat(heldFinished).isTrue();
    assertThat(subjects.pulled()).isEqualTo(2);
    assertThat(admission.inFlight()).isZero();
  }

  @Test
  @DisplayName("Should admit no further task when the only admitted task throws")
  void shouldAdmitNoFurtherTaskWhenTheOnlyAdmittedTaskThrows() throws Exception {
    var admission = admissionWith(FixedLimit.of(1));
    var failure = new IllegalStateException("task failed");
    var subjects = new CountingSubjects(3);

    var failures =
        admission.forkAdmittedTasks(
            subjects,
            _ -> {
              throw failure;
            },
            THREADS);

    assertThat(failures).containsExactly(failure);
    assertThat(subjects.pulled()).isEqualTo(1);
    assertThat(admission.inFlight()).isZero();
  }

  @Test
  @DisplayName("Should return task failures in admission order")
  void shouldReturnTaskFailuresInAdmissionOrder() throws Exception {
    var admission = admissionWith(FixedLimit.of(2));
    var first = new IllegalStateException("first admitted");
    var second = new IllegalStateException("second admitted");
    AdmittedTask<Integer> task =
        subject -> {
          if (subject == 0) {
            listener.awaitReport();
            throw first;
          }

          throw second;
        };

    var failures = admission.forkAdmittedTasks(new CountingSubjects(2), task, THREADS);

    assertThat(failures).containsExactly(first, second);
    assertThat(admission.inFlight()).isZero();
  }

  @Test
  @DisplayName("Should report a drop when the task marked a retry")
  void shouldReportADropWhenTheTaskMarkedARetry() throws Exception {
    var admission = admissionWith(FixedLimit.of(1));

    admission.forkAdmittedTasks(
        new CountingSubjects(1),
        _ -> {
          AdmittedTaskRetries.markRetried();
          return AdmittedTaskReport.LATENCY;
        },
        THREADS);

    assertThat(listener.reports()).containsExactly(AdmittedTaskReport.DROP);
  }

  @ParameterizedTest
  @MethodSource("timeouts")
  @DisplayName("Should report a drop when a task throws because of a timeout")
  void shouldReportADropWhenATaskThrowsBecauseOfATimeout(Throwable timeout) throws Exception {
    var admission = admissionWith(FixedLimit.of(1));
    var failure = new IllegalStateException("wrapped", timeout);

    var failures =
        admission.forkAdmittedTasks(
            new CountingSubjects(1),
            _ -> {
              throw failure;
            },
            THREADS);

    assertThat(failures).containsExactly(failure);
    assertThat(listener.reports()).containsExactly(AdmittedTaskReport.DROP);
  }

  static Stream<Throwable> timeouts() {
    return Stream.of(
        new SQLTransientConnectionException("pool exhausted"),
        new HttpTimeoutException("request timed out"),
        new SocketTimeoutException("read timed out"),
        new TimeoutException("timed out"));
  }

  @Test
  @DisplayName("Should report a drop when a retried task throws")
  void shouldReportADropWhenARetriedTaskThrows() throws Exception {
    var admission = admissionWith(FixedLimit.of(1));

    admission.forkAdmittedTasks(
        new CountingSubjects(1),
        _ -> {
          AdmittedTaskRetries.markRetried();
          throw new IllegalStateException("failed after a retry");
        },
        THREADS);

    assertThat(listener.reports()).containsExactly(AdmittedTaskReport.DROP);
  }

  @Test
  @DisplayName("Should interrupt admitted tasks and rethrow when the walking thread is interrupted")
  void shouldInterruptAdmittedTasksAndRethrowWhenTheWalkingThreadIsInterrupted() throws Exception {
    var admission = admissionWith(FixedLimit.of(1));
    var taskStarted = new CountDownLatch(1);
    var taskInterrupted = new AtomicBoolean();
    AdmittedTask<Integer> task =
        _ -> {
          taskStarted.countDown();
          try {
            new CountDownLatch(1).await();
          } catch (InterruptedException interrupted) {
            taskInterrupted.set(true);
            throw interrupted;
          }

          return AdmittedTaskReport.LATENCY;
        };

    try (var walk =
        BoundedTask.start(
            () -> admission.forkAdmittedTasks(new CountingSubjects(2), task, THREADS))) {
      assertThat(taskStarted.await(BOUND.toNanos(), TimeUnit.NANOSECONDS)).isTrue();

      walk.interrupt();

      assertThatThrownBy(() -> walk.await(BOUND)).isInstanceOf(InterruptedException.class);
    }

    assertThat(taskInterrupted).isTrue();
    assertThat(admission.inFlight()).isZero();
  }

  @Test
  @DisplayName(
      "Should admit no further task when the walking thread is interrupted with admissions free")
  void shouldAdmitNoFurtherTaskWhenTheWalkingThreadIsInterruptedWithAdmissionsFree()
      throws Exception {
    var admission = admissionWith(FixedLimit.of(10));
    var pulled = new AtomicInteger();
    Iterator<Integer> subjects =
        new Iterator<>() {
          @Override
          public boolean hasNext() {
            return pulled.get() < 3;
          }

          @Override
          public Integer next() {
            Thread.currentThread().interrupt();
            return pulled.getAndIncrement();
          }
        };

    try (var walk =
        BoundedTask.start(
            () ->
                admission.forkAdmittedTasks(subjects, _ -> AdmittedTaskReport.LATENCY, THREADS))) {
      assertThatThrownBy(() -> walk.await(BOUND)).isInstanceOf(InterruptedException.class);
    }

    assertThat(pulled).hasValue(1);
    assertThat(admission.inFlight()).isZero();
  }

  @Test
  @DisplayName(
      "Should rethrow the interrupt when the walking thread is interrupted after its tasks finished")
  void shouldRethrowTheInterruptWhenTheWalkingThreadIsInterruptedAfterItsTasksFinished()
      throws Exception {
    var admission = admissionWith(FixedLimit.of(3));
    var tasksFinished = new CountDownLatch(2);
    ThreadFactory threadsThatSignalTheirEnd =
        runnable ->
            THREADS.newThread(
                () -> {
                  try {
                    runnable.run();
                  } finally {
                    tasksFinished.countDown();
                  }
                });
    var pulled = new AtomicInteger();
    Iterator<Integer> subjects =
        new Iterator<>() {
          @Override
          public boolean hasNext() {
            if (pulled.get() < 2) {
              return true;
            }

            awaitWithinBound(tasksFinished);
            Thread.currentThread().interrupt();
            return false;
          }

          @Override
          public Integer next() {
            return pulled.getAndIncrement();
          }
        };

    try (var walk =
        BoundedTask.start(
            () ->
                admission.forkAdmittedTasks(
                    subjects, _ -> AdmittedTaskReport.LATENCY, threadsThatSignalTheirEnd))) {
      assertThatThrownBy(() -> walk.await(BOUND)).isInstanceOf(InterruptedException.class);
    }

    assertThat(listener.reports())
        .containsExactly(AdmittedTaskReport.LATENCY, AdmittedTaskReport.LATENCY);
    assertThat(admission.inFlight()).isZero();
  }

  @Test
  @DisplayName("Should tell the listener once when the admission closes")
  void shouldTellTheListenerOnceWhenTheAdmissionCloses() {
    var admission = admissionWith(FixedLimit.of(1));

    admission.close();
    admission.close();

    assertThat(listener.closes()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should admit tasks without a listener when none is set")
  void shouldAdmitTasksWithoutAListenerWhenNoneIsSet() throws Exception {
    var admission = AdaptiveAdmission.builder().limit(FixedLimit.of(1)).nanoClock(clock).build();

    var failures =
        admission.forkAdmittedTasks(
            new CountingSubjects(2), _ -> AdmittedTaskReport.LATENCY, THREADS);
    admission.close();

    assertThat(failures).isEmpty();
    assertThat(admission.inFlight()).isZero();
  }

  @Test
  @DisplayName("Should reject a missing limit or clock when the admission is built")
  void shouldRejectAMissingLimitOrClockWhenTheAdmissionIsBuilt() {
    var withoutLimit = AdaptiveAdmission.builder().nanoClock(clock);
    var withoutClock = AdaptiveAdmission.builder().limit(FixedLimit.of(1));

    assertThatNullPointerException().isThrownBy(withoutLimit::build).withMessageContaining("limit");
    assertThatNullPointerException()
        .isThrownBy(withoutClock::build)
        .withMessageContaining("nanoClock");
  }

  @Test
  @DisplayName("Should reject missing subjects, task or threads when forking admitted tasks")
  void shouldRejectMissingSubjectsTaskOrThreadsWhenForkingAdmittedTasks() {
    var admission = admissionWith(FixedLimit.of(1));
    AdmittedTask<Integer> task = _ -> AdmittedTaskReport.LATENCY;
    var subjects = new CountingSubjects(1);

    assertThatNullPointerException()
        .isThrownBy(() -> admission.forkAdmittedTasks(null, task, THREADS))
        .withMessageContaining("subjects");
    assertThatNullPointerException()
        .isThrownBy(() -> admission.forkAdmittedTasks(subjects, null, THREADS))
        .withMessageContaining("task");
    assertThatNullPointerException()
        .isThrownBy(() -> admission.forkAdmittedTasks(subjects, task, null))
        .withMessageContaining("threads");
  }

  private static void awaitWithinBound(CountDownLatch latch) {
    try {
      assertThat(latch.await(BOUND.toNanos(), TimeUnit.NANOSECONDS)).isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private AdaptiveAdmission learnedAdmission() {
    return admissionWith(AdaptiveAdmission.learnedLimit());
  }

  private AdaptiveAdmission admissionWith(Limit limit) {
    return AdaptiveAdmission.builder().limit(limit).nanoClock(clock).listener(listener).build();
  }

  private BoundedTask<List<Throwable>> startWalk(
      AdaptiveAdmission admission, Iterator<Integer> subjects, AdmittedTask<Integer> tasks) {
    return BoundedTask.start(
        () -> {
          clock.walkOn(Thread.currentThread());
          return admission.forkAdmittedTasks(subjects, tasks, THREADS);
        });
  }

  // Each report returns one admission, and the walk then admits until the limit is full again.
  private void releaseInAdmissionOrder(AdaptiveAdmission admission, HeldTasks tasks, int reports)
      throws InterruptedException {
    tasks.awaitStarted(admission.limit() - tasks.heldCount());
    for (var i = 0; i < reports; i++) {
      tasks.releaseOldest();
      listener.awaitReport();
      tasks.awaitStarted(admission.limit() - tasks.heldCount());
    }
  }

  private static void finish(
      BoundedTask<List<Throwable>> walk, EndlessSubjects subjects, HeldTasks tasks)
      throws Exception {
    subjects.end();
    tasks.releaseAll();
    assertThat(walk.await(BOUND)).isEmpty();
  }

  /**
   * Reads zero on the walking thread, where each admission starts, and the current task latency on
   * task threads, where each report ends one, so every sampled report measures that latency.
   */
  private static final class TaskLatencyClock implements LongSupplier {

    private volatile Thread walker;
    private volatile long taskLatencyNanos = Duration.ofMillis(1).toNanos();

    void walkOn(Thread thread) {
      walker = thread;
    }

    void taskLatency(Duration latency) {
      taskLatencyNanos = latency.toNanos();
    }

    @Override
    public long getAsLong() {
      if (Thread.currentThread() == walker) {
        return 0;
      }

      return taskLatencyNanos;
    }
  }

  /** Subjects that never run out until the test ends them. */
  private static final class EndlessSubjects implements Iterator<Integer> {

    private volatile boolean ended;
    private int next;

    void end() {
      ended = true;
    }

    @Override
    public boolean hasNext() {
      return !ended;
    }

    @Override
    public Integer next() {
      if (ended) {
        throw new NoSuchElementException();
      }

      return next++;
    }
  }

  /** A fixed number of subjects that counts each pull and the tasks in flight at each pull. */
  private static final class CountingSubjects implements Iterator<Integer> {

    private final int count;
    private final AdaptiveAdmission admission;
    private final List<Integer> inFlightAtEachPull = new CopyOnWriteArrayList<>();
    private final AtomicInteger pulled = new AtomicInteger();

    CountingSubjects(int count) {
      this(count, null);
    }

    CountingSubjects(int count, AdaptiveAdmission admission) {
      this.count = count;
      this.admission = admission;
    }

    @Override
    public boolean hasNext() {
      return pulled.get() < count;
    }

    @Override
    public Integer next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }

      if (admission != null) {
        inFlightAtEachPull.add(admission.inFlight());
      }

      return pulled.getAndIncrement();
    }

    int pulled() {
      return pulled.get();
    }

    List<Integer> inFlightAtEachPull() {
      return inFlightAtEachPull;
    }
  }

  /** Admitted tasks that each wait until the test releases them, oldest first. */
  private static final class HeldTasks implements AdmittedTask<Integer> {

    private static final CountDownLatch OPEN = new CountDownLatch(0);

    private final Function<Integer, AdmittedTaskReport> report;
    private final BlockingQueue<CountDownLatch> started = new LinkedBlockingQueue<>();
    private final Deque<CountDownLatch> held = new ArrayDeque<>();
    private boolean releasedAll;

    HeldTasks(Function<Integer, AdmittedTaskReport> report) {
      this.report = report;
    }

    @Override
    public AdmittedTaskReport run(Integer subject) throws InterruptedException {
      var release = register();
      if (!release.await(BOUND.toNanos(), TimeUnit.NANOSECONDS)) {
        throw new AssertionError("Task " + subject + " was not released within " + BOUND);
      }

      return report.apply(subject);
    }

    private synchronized CountDownLatch register() {
      if (releasedAll) {
        return OPEN;
      }

      var release = new CountDownLatch(1);
      started.add(release);
      return release;
    }

    void awaitStarted(int count) throws InterruptedException {
      for (var i = 0; i < count; i++) {
        var release = started.poll(BOUND.toNanos(), TimeUnit.NANOSECONDS);
        assertThat(release).as("admitted task started within " + BOUND).isNotNull();
        held.addLast(release);
      }
    }

    int heldCount() {
      return held.size();
    }

    void releaseOldest() {
      held.removeFirst().countDown();
    }

    synchronized void releaseAll() {
      releasedAll = true;
      held.forEach(CountDownLatch::countDown);
      started.forEach(CountDownLatch::countDown);
    }
  }

  /** Captures what the admission emits to its listener, as metrics read it. */
  private static final class CapturingListener implements AdmissionListener {

    private final BlockingQueue<AdmittedTaskReport> reports = new LinkedBlockingQueue<>();
    private final List<AdmittedTaskReport> allReports = new CopyOnWriteArrayList<>();
    private final AtomicInteger admitted = new AtomicInteger();

    private final AtomicInteger closes = new AtomicInteger();

    @Override
    public void onAdmitted() {
      admitted.incrementAndGet();
    }

    @Override
    public void onReported(AdmittedTaskReport report) {
      allReports.add(report);
      reports.add(report);
    }

    @Override
    public void onClosed() {
      closes.incrementAndGet();
    }

    int admitted() {
      return admitted.get();
    }

    List<AdmittedTaskReport> reports() {
      return List.copyOf(allReports);
    }

    int closes() {
      return closes.get();
    }

    void awaitReport() throws InterruptedException {
      assertThat(reports.poll(BOUND.toNanos(), TimeUnit.NANOSECONDS))
          .as("report within " + BOUND)
          .isNotNull();
    }

    long reportsOf(AdmittedTaskReport kind) {
      return allReports.stream().filter(kind::equals).count();
    }
  }
}
