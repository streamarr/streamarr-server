package com.streamarr.server.services.library.admission;

import com.streamarr.server.config.http.PacedTmdbGate;
import com.streamarr.server.poc.HikariLive;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Variant D: a pipeline of stages connected by rendezvous handoffs ({@link SynchronousQueue}, no
 * capacity), with no fixed worker count in any stage in the default register mode ({@code
 * fanout}); the opt-in {@code serial} register mode has a fixed worker count of one.
 *
 * <pre>
 * scan:    walk -> register (database) -> identify (remote) -> persist (database)
 * refresh: targets -> identify (remote) -> persist (database)
 * </pre>
 *
 * <ul>
 *   <li><b>walk</b> pulls the next item only when <b>register</b> takes the previous one, so a stalled
 *       register stage stops the walk.
 *   <li><b>register</b> has one dispatcher that forks one virtual thread per item while no thread
 *       waits for a pooled database connection, no register task is blocked handing off to
 *       identify, and fewer register tasks are doing database work than the pool has connections
 *       to spare. Short-circuited items (unsupported, already matched) end here. For a refresh the
 *       targets need no registration, so the target loop registers inline.
 *   <li><b>identify</b> has one dispatcher that takes the next registered item only while the TMDB
 *       gate's queued core schedule ({@link PacedTmdbGate#coreBacklogNanos()}: background artwork
 *       slots left out, so secondary artwork cannot hold identify shut) plus the demand of forked
 *       identify tasks that have not yet reached the gate is below one item's worth of requests,
 *       and no identify task is blocked handing its result to persist. It then forks one virtual
 *       thread for that item.
 *   <li><b>persist</b> has one dispatcher that takes the next identified item only while no thread
 *       waits for a pooled database connection, then forks one virtual thread for it.
 * </ul>
 *
 * <p>Backpressure: a slow persist stage leaves identify tasks blocked in their handoff; the identify
 * dispatcher then stops taking, register blocks handing off, and the walk blocks. A saturated TMDB
 * gate stops the identify dispatcher the same way.
 *
 * <p>Cost: {@link StagedTask#identify} for a scan fetches remote details unconditionally, outside
 * the provider-id mutex; the original path fetches them only when the movie does not exist yet. A
 * second file of the same movie therefore spends one extra details request (and gate permit).
 *
 * <p>Failures are collected per item and returned in pull order, exactly as the unbounded strategy
 * does, so one failed item still marks the scan unhealthy without stopping the others. Any {@link
 * Throwable} a stage call throws (an {@link Error} included) fails only its item. A stage thread
 * that dies anyway cancels the run: every stage is interrupted, every unfinished item fails with
 * that cause, and the cause is returned as one more failure. Cancellation (the calling thread
 * interrupted) interrupts every stage and worker, and the end-of-stream marker ({@link
 * Optional#empty()}) plays the role of closing a channel. No admitted item is left without a
 * result: after the stages end, an item still unfinished fails.
 *
 * <p>{@code poc.pipeline.register=serial} (default {@code fanout}) replaces the register dispatcher
 * with one thread that registers one item at a time, to measure what that costs on an unchanged
 * rescan, where register is the whole scan.
 *
 * <p>{@code poc.admission.limit} reports the identify stage's emergent concurrency: forked identify
 * tasks still doing remote work. When {@code poc.sampler.file} is set, each run also appends a
 * per-stage trace to the same path with {@code -pipeline} before the extension.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "poc.admission", havingValue = "pipeline")
public class PipelineFileAdmission implements FileAdmission, AdmissionRuntime.Listener {

  private static final long MIN_PAUSE_NANOS = 200_000L;
  private static final long MAX_PAUSE_NANOS = 5_000_000L;
  private static final Duration HANDOFF_PAUSE = Duration.ofMillis(1);
  private static final Duration POOL_PAUSE = Duration.ofMillis(1);
  private static final long HANDOFF_OFFER_MILLIS = 100;

  private final AdmissionRuntime runtime;
  private final PacedTmdbGate gate;
  private final HikariLive hikari;
  private final PipelineTrace trace;
  private final boolean registerFanout;

  /** Forked identify tasks (every run) that have not yet reserved a TMDB gate slot. */
  private final Set<AdmissionTicket> identifyInTransit = ConcurrentHashMap.newKeySet();

  /** Forked identify tasks (every run) still doing remote work. */
  private final AtomicInteger identifyActive = new AtomicInteger();

  public PipelineFileAdmission(
      AdmissionRuntime runtime,
      PacedTmdbGate gate,
      HikariLive hikari,
      @Value("${poc.sampler.file:}") String samplerFile,
      @Value("${poc.sampler.period:250ms}") Duration samplerPeriod,
      @Value("${poc.pipeline.register:fanout}") String registerMode) {
    this.runtime = runtime;
    this.registerFanout = "fanout".equals(registerMode);
    this.gate = gate;
    this.hikari = hikari;
    this.trace = new PipelineTrace(samplerFile, samplerPeriod);
    runtime.addListener(this);
    runtime.setAdmissionLimit(identifyActive::get);
    log.info("POC pipeline register stage: {}", registerFanout ? "fanout" : "serial");
  }

  @Override
  public String name() {
    return "pipeline";
  }

  @Override
  public void onLeftTransit(AdmissionTicket ticket) {
    identifyInTransit.remove(ticket);
  }

  @Override
  public <T> List<Throwable> processAll(Workload workload, Stream<T> items, AdmittedTask<T> task)
      throws InterruptedException {
    if (task instanceof StagedTask<T, ?, ?> staged) {
      return run(workload, items, Stages.of(staged));
    }

    return run(workload, items, Stages.whole(task));
  }

  private <T, R, I> List<Throwable> run(Workload workload, Stream<T> items, Stages<T, R, I> stages)
      throws InterruptedException {
    return new Run<>(workload, stages).execute(items);
  }

  /** The three stage calls; {@link #persist} returns the item's outcome. */
  private interface Stages<T, R, I> {
    Optional<R> register(T item) throws Exception;

    I identify(R registered) throws Exception;

    TaskOutcome persist(I identified) throws Exception;

    static <T, R, I> Stages<T, R, I> of(StagedTask<T, R, I> task) {
      return new Stages<>() {
        @Override
        public Optional<R> register(T item) throws Exception {
          return task.register(item);
        }

        @Override
        public I identify(R registered) throws Exception {
          return task.identify(registered);
        }

        @Override
        public TaskOutcome persist(I identified) throws Exception {
          task.persist(identified);
          return TaskOutcome.WORKED;
        }
      };
    }

    /** A task that is not split into stages runs whole, gated as remote work. */
    static <T> Stages<T, T, TaskOutcome> whole(AdmittedTask<T> task) {
      return new Stages<>() {
        @Override
        public Optional<T> register(T item) {
          return Optional.of(item);
        }

        @Override
        public TaskOutcome identify(T item) throws Exception {
          return task.run(item);
        }

        @Override
        public TaskOutcome persist(TaskOutcome outcome) {
          return outcome;
        }
      };
    }
  }

  /** One pulled item: its ticket and the future its outcome or failure completes. */
  private record Admitted(AdmissionTicket ticket, CompletableFuture<TaskOutcome> result) {}

  /** An item between stages. */
  private record Job<V>(Admitted admitted, V value) {
    <W> Job<W> next(W nextValue) {
      return new Job<>(admitted, nextValue);
    }

    AdmissionTicket ticket() {
      return admitted.ticket();
    }
  }

  /** Per-run counters for the trace and the end-of-run summary. */
  static final class Counters {
    final LongAdder walked = new LongAdder();
    final LongAdder registered = new LongAdder();
    final LongAdder shortCircuited = new LongAdder();
    final LongAdder identifyForked = new LongAdder();
    final LongAdder persistForked = new LongAdder();
    final LongAdder identifyWaitNanos = new LongAdder();
    final LongAdder persistWaitNanos = new LongAdder();
    final AtomicInteger persistActive = new AtomicInteger();
    final AtomicInteger registerActive = new AtomicInteger();
    final AtomicInteger registerBlocked = new AtomicInteger();
    final AtomicInteger peakRegisterActive = new AtomicInteger();
    final AtomicInteger blockedHandoffs = new AtomicInteger();
    final AtomicInteger peakIdentifyActive = new AtomicInteger();
    final AtomicInteger peakPersistActive = new AtomicInteger();
    final AtomicInteger peakBlockedHandoffs = new AtomicInteger();
    final AtomicInteger peakInFlight = new AtomicInteger();

    static void raise(AtomicInteger peak, int value) {
      peak.accumulateAndGet(value, Math::max);
    }
  }

  private final class Run<T, R, I> {

    private final Workload workload;
    private final Stages<T, R, I> stages;
    private final boolean registerInline;
    private final SynchronousQueue<Optional<Job<T>>> toRegister = new SynchronousQueue<>(true);
    private final SynchronousQueue<Optional<Job<R>>> toIdentify = new SynchronousQueue<>(true);
    private final SynchronousQueue<Optional<Job<I>>> toPersist = new SynchronousQueue<>(true);
    private final List<Admitted> admitted = Collections.synchronizedList(new ArrayList<>());
    private final Counters counters = new Counters();
    private final AtomicLong callsPerItemBits;
    private volatile RuntimeException walkFailure;
    private volatile boolean cancelled;
    private final AtomicReference<Throwable> stageFailure = new AtomicReference<>();
    private volatile ExecutorService stageThreads;

    private Run(Workload workload, Stages<T, R, I> stages) {
      this.workload = workload;
      this.stages = stages;
      this.registerInline = workload == Workload.REFRESH;
      // Prior for the TMDB requests one identify task makes: search + details for a scan, details
      // for a refresh. Replaced by the observed average as tasks finish.
      this.callsPerItemBits =
          new AtomicLong(Double.doubleToLongBits(workload == Workload.SCAN ? 2.0 : 1.0));
    }

    List<Throwable> execute(Stream<T> items) throws InterruptedException {
      var startNanos = System.nanoTime();
      var label = workload.name().toLowerCase();
      var stageThreads =
          Executors.newThreadPerTaskExecutor(
              Thread.ofVirtual().name("poc-pipeline-" + label + "-stage-", 0).factory());
      this.stageThreads = stageThreads;
      var sampler = trace.start(label, this::traceRow);

      try {
        var running = new ArrayList<Future<?>>();
        running.add(stageThreads.submit(guarded(() -> walk(items))));
        if (!registerInline) {
          running.add(stageThreads.submit(guarded(this::register)));
        }
        running.add(stageThreads.submit(guarded(this::identify)));
        running.add(stageThreads.submit(guarded(this::persist)));

        awaitStages(stageThreads, running);
        stageThreads.close();
        failUnfinished(new IllegalStateException("POC pipeline ended without finishing the item"));
      } finally {
        trace.stop(sampler);
        for (var item : snapshot()) {
          identifyInTransit.remove(item.ticket());
        }
      }

      var failures =
          new ArrayList<>(
              AdmissionFutures.awaitInOrder(snapshot().stream().map(Admitted::result).toList()));
      var stageCause = stageFailure.get();
      if (stageCause != null) {
        failures.add(stageCause);
      }

      logSummary(label, startNanos, failures.size());

      if (walkFailure != null) {
        throw walkFailure;
      }

      return failures;
    }

    /**
     * A stage that throws records the first failure and interrupts every stage, so no stage is left
     * waiting on a handoff that a dead stage will never serve.
     */
    private Callable<Void> guarded(Callable<Void> stage) {
      return () -> {
        try {
          return stage.call();
        } catch (Throwable failure) {
          if (!cancelled && stageFailure.compareAndSet(null, failure)) {
            cancelled = true;
            stageThreads.shutdownNow();
          }

          throw failure;
        }
      };
    }

    /** Joins every stage (a failed stage has already interrupted the others). */
    private void awaitStages(ExecutorService stageThreads, List<Future<?>> running)
        throws InterruptedException {
      try {
        for (var stage : running) {
          try {
            stage.get();
          } catch (ExecutionException _) {
            // The guard recorded the first failure; later ones are the interrupts it caused.
          }
        }
      } catch (InterruptedException exception) {
        cancel(stageThreads, new InterruptedException("POC pipeline cancelled"));
        Thread.currentThread().interrupt();
        throw exception;
      }

      var cause = stageFailure.get();
      if (cause != null) {
        cancel(stageThreads, new IllegalStateException("POC pipeline stage failed", cause));
      }
    }

    private void cancel(ExecutorService stageThreads, Throwable itemFailure) {
      cancelled = true;
      stageThreads.shutdownNow();
      stageThreads.close();
      failUnfinished(itemFailure);
    }

    private void failUnfinished(Throwable itemFailure) {
      for (var item : snapshot()) {
        if (item.result().isDone()) {
          continue;
        }

        item.ticket().fail();
        item.result().completeExceptionally(itemFailure);
      }
    }

    private List<Admitted> snapshot() {
      synchronized (admitted) {
        return List.copyOf(admitted);
      }
    }

    // ---- walk ----------------------------------------------------------------------------

    private Void walk(Stream<T> items) throws InterruptedException {
      var iterator = items.iterator();

      while (true) {
        T item;
        try {
          if (!iterator.hasNext()) {
            break;
          }

          item = iterator.next();
        } catch (RuntimeException failure) {
          walkFailure = failure;
          break;
        }

        var job = admit(item);
        if (registerInline) {
          registerOne(job);
          continue;
        }

        if (!handOff(toRegister, Optional.of(job))) {
          return null;
        }
      }

      if (registerInline) {
        handOff(toIdentify, Optional.empty());
        return null;
      }

      handOff(toRegister, Optional.empty());
      return null;
    }

    private Job<T> admit(T item) {
      var entry = new Admitted(runtime.admit(), new CompletableFuture<>());
      admitted.add(entry);
      counters.walked.increment();
      Counters.raise(counters.peakInFlight, runtime.inFlight());
      return new Job<>(entry, item);
    }

    // ---- register (serial) ---------------------------------------------------------------

    private Void register() throws InterruptedException {
      if (registerFanout) {
        registerFanout();
      } else {
        for (Optional<Job<T>> next; (next = toRegister.take()).isPresent(); ) {
          registerOne(next.get());
        }
      }

      handOff(toIdentify, Optional.empty());
      return null;
    }

    private void registerFanout() throws InterruptedException {
      try (var workers =
          Executors.newThreadPerTaskExecutor(
              Thread.ofVirtual().name("poc-pipeline-register-", 0).factory())) {
        try {
          while (true) {
            awaitRegisterCapacity();
            var next = toRegister.take();
            if (next.isEmpty()) {
              break;
            }

            awaitRegisterCapacity();
            var job = next.get();
            Counters.raise(
                counters.peakRegisterActive, counters.registerActive.incrementAndGet());
            try {
              workers.execute(() -> registerForked(job));
            } catch (Throwable notForked) {
              counters.registerActive.decrementAndGet();
              fail(job, notForked);
              throw notForked;
            }
          }
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw exception;
        }
      }
    }

    /**
     * Forked register tasks reach the pool a moment after the fork, so the pool's waiter count
     * lags; register tasks still doing database work are therefore held below the connections the
     * pool has to spare as well.
     */
    private void awaitRegisterCapacity() throws InterruptedException {
      while (counters.registerBlocked.get() > 0
          || hikari.awaiting() > 0
          || counters.registerActive.get() - counters.registerBlocked.get() >= hikari.spare()) {
        Thread.sleep(POOL_PAUSE);
      }
    }

    private void registerForked(Job<T> job) {
      try {
        registerOne(job);
      } catch (Throwable failure) {
        fail(job, failure);
      } finally {
        counters.registerActive.decrementAndGet();
      }
    }

    private void registerOne(Job<T> job) throws InterruptedException {
      Optional<R> registered;
      try {
        registered = job.ticket().within(() -> stages.register(job.value()));
      } catch (Throwable failure) {
        fail(job, failure);
        return;
      }

      if (registered.isEmpty()) {
        counters.shortCircuited.increment();
        finish(job, TaskOutcome.SHORT_CIRCUITED);
        return;
      }

      counters.registered.increment();
      counters.registerBlocked.incrementAndGet();
      try {
        if (!handOff(toIdentify, Optional.of(job.next(registered.get())))) {
          fail(job, new InterruptedException("POC pipeline cancelled"));
        }
      } finally {
        counters.registerBlocked.decrementAndGet();
      }
    }

    // ---- identify (TMDB-gated fan-out) ---------------------------------------------------

    private Void identify() throws InterruptedException {
      try (var workers =
          Executors.newThreadPerTaskExecutor(
              Thread.ofVirtual().name("poc-pipeline-identify-", 0).factory())) {
        try {
          while (true) {
            awaitIdentifyCapacity();
            var next = toIdentify.take();
            if (next.isEmpty()) {
              break;
            }

            awaitIdentifyCapacity();
            var job = next.get();
            identifyInTransit.add(job.ticket());
            Counters.raise(counters.peakIdentifyActive, identifyActive.incrementAndGet());
            counters.identifyForked.increment();
            try {
              workers.execute(() -> identifyOne(job));
            } catch (Throwable notForked) {
              identifyEnded(job);
              fail(job, notForked);
              throw notForked;
            }
          }
        } catch (InterruptedException exception) {
          // Leave the flag set so closing the workers interrupts them.
          Thread.currentThread().interrupt();
          throw exception;
        }
      }

      handOff(toPersist, Optional.empty());
      return null;
    }

    private void awaitIdentifyCapacity() throws InterruptedException {
      var start = System.nanoTime();

      while (true) {
        if (counters.blockedHandoffs.get() > 0) {
          Thread.sleep(HANDOFF_PAUSE);
          continue;
        }

        var perItem = Math.max(1.0, callsPerItem());
        var interval = gate.intervalNanos();
        var threshold = (long) (perItem * interval);
        var inTransit = (long) (identifyInTransit.size() * perItem * interval);
        var demand = gate.coreBacklogNanos() + inTransit;
        if (demand < threshold) {
          break;
        }

        Thread.sleep(Duration.ofNanos(Math.clamp(demand - threshold, MIN_PAUSE_NANOS, MAX_PAUSE_NANOS)));
      }

      counters.identifyWaitNanos.add(System.nanoTime() - start);
    }

    /** Ends the item's identify accounting exactly once, however the item ends. */
    private void identifyOne(Job<R> job) {
      var ended = false;
      try {
        I identified;
        try {
          identified = job.ticket().within(() -> stages.identify(job.value()));
        } catch (Throwable failure) {
          fail(job, failure);
          return;
        }

        observeCalls(job.ticket().tmdbApiReservations());
        // Blocked before identify ends, so the dispatcher never sees a gap in which to fork.
        Counters.raise(counters.peakBlockedHandoffs, counters.blockedHandoffs.incrementAndGet());
        try {
          identifyEnded(job);
          ended = true;
          if (!handOff(toPersist, Optional.of(job.next(identified)))) {
            fail(job, new InterruptedException("POC pipeline cancelled"));
          }
        } catch (Throwable failure) {
          fail(job, failure);
        } finally {
          counters.blockedHandoffs.decrementAndGet();
        }
      } finally {
        if (!ended) {
          identifyEnded(job);
        }
      }
    }

    private void identifyEnded(Job<R> job) {
      identifyInTransit.remove(job.ticket());
      identifyActive.decrementAndGet();
    }

    private double callsPerItem() {
      return Double.longBitsToDouble(callsPerItemBits.get());
    }

    private void observeCalls(int calls) {
      callsPerItemBits.updateAndGet(
          bits -> Double.doubleToLongBits(0.9 * Double.longBitsToDouble(bits) + 0.1 * calls));
    }

    // ---- persist (pool-gated fan-out) ----------------------------------------------------

    private Void persist() throws InterruptedException {
      try (var workers =
          Executors.newThreadPerTaskExecutor(
              Thread.ofVirtual().name("poc-pipeline-persist-", 0).factory())) {
        try {
          while (true) {
            awaitPersistCapacity();
            var next = toPersist.take();
            if (next.isEmpty()) {
              break;
            }

            awaitPersistCapacity();
            var job = next.get();
            Counters.raise(
                counters.peakPersistActive, counters.persistActive.incrementAndGet());
            counters.persistForked.increment();
            try {
              workers.execute(() -> persistOne(job));
            } catch (Throwable notForked) {
              counters.persistActive.decrementAndGet();
              fail(job, notForked);
              throw notForked;
            }
          }
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw exception;
        }
      }

      return null;
    }

    private void awaitPersistCapacity() throws InterruptedException {
      var start = System.nanoTime();
      while (hikari.awaiting() > 0) {
        Thread.sleep(POOL_PAUSE);
      }

      counters.persistWaitNanos.add(System.nanoTime() - start);
    }

    private void persistOne(Job<I> job) {
      try {
        finish(job, job.ticket().within(() -> stages.persist(job.value())));
      } catch (Throwable failure) {
        fail(job, failure);
      } finally {
        counters.persistActive.decrementAndGet();
      }
    }

    // ---- shared --------------------------------------------------------------------------

    /**
     * Rendezvous with the next stage. Returns false when the run was cancelled while waiting; the
     * timed offer only guards against a cancelled consumer that will never take again.
     */
    private <V> boolean handOff(SynchronousQueue<Optional<V>> queue, Optional<V> value)
        throws InterruptedException {
      while (!queue.offer(value, HANDOFF_OFFER_MILLIS, TimeUnit.MILLISECONDS)) {
        if (cancelled) {
          return false;
        }
      }

      return true;
    }

    private void finish(Job<?> job, TaskOutcome outcome) {
      job.ticket().finish(outcome);
      job.admitted().result().complete(outcome);
    }

    private void fail(Job<?> job, Throwable failure) {
      job.ticket().fail();
      job.admitted().result().completeExceptionally(failure);
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }

    private String traceRow() {
      return String.join(
          ",",
          Long.toString(System.currentTimeMillis()),
          workload.name().toLowerCase(),
          Long.toString(counters.walked.sum()),
          Long.toString(counters.registered.sum()),
          Long.toString(counters.shortCircuited.sum()),
          Long.toString(counters.identifyForked.sum()),
          Integer.toString(identifyActive.get()),
          Integer.toString(identifyInTransit.size()),
          Integer.toString(counters.blockedHandoffs.get()),
          Long.toString(counters.persistForked.sum()),
          Integer.toString(counters.persistActive.get()),
          PipelineTrace.decimal(callsPerItem()),
          PipelineTrace.decimal(counters.identifyWaitNanos.sum() / 1e6),
          PipelineTrace.decimal(counters.persistWaitNanos.sum() / 1e6),
          PipelineTrace.decimal(gate.backlogNanos() / 1e6),
          Integer.toString(hikari.awaiting()),
          Integer.toString(runtime.inFlight()),
          Integer.toString(counters.registerActive.get()),
          Integer.toString(counters.registerBlocked.get()));
    }

    private void logSummary(String label, long startNanos, int failures) {
      log.info(
          "POC pipeline {} finished in {} ms: walked={} registered={} shortCircuited={}"
              + " identifyForked={} persistForked={} failures={} peakInFlight={}"
              + " peakIdentify={} peakPersist={} peakBlockedHandoffs={} callsPerItem={}"
              + " identifyWaitMs={} persistWaitMs={} register={} peakRegister={}",
          label,
          (System.nanoTime() - startNanos) / 1_000_000,
          counters.walked.sum(),
          counters.registered.sum(),
          counters.shortCircuited.sum(),
          counters.identifyForked.sum(),
          counters.persistForked.sum(),
          failures,
          counters.peakInFlight.get(),
          counters.peakIdentifyActive.get(),
          counters.peakPersistActive.get(),
          counters.peakBlockedHandoffs.get(),
          PipelineTrace.decimal(callsPerItem()),
          counters.identifyWaitNanos.sum() / 1_000_000,
          counters.persistWaitNanos.sum() / 1_000_000,
          registerFanout ? "fanout" : "serial",
          counters.peakRegisterActive.get());
    }
  }
}
