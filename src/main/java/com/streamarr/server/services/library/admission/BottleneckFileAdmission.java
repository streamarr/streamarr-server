package com.streamarr.server.services.library.admission;

import com.streamarr.server.config.http.PacedTmdbGate;
import com.streamarr.server.poc.HikariLive;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Variant B: the walker admits the next item only while the shared bottlenecks have room, so there
 * is no fixed concurrency number anywhere.
 *
 * <p>Admission rule, re-evaluated before every item:
 *
 * <pre>
 *   gate backlog + demand in transit  &lt;  one TMDB-bound item's worth + no-load transit time
 *   and  Hikari threads awaiting a connection == 0
 * </pre>
 *
 * <p>Read as: the backlog projected to the moment the next item reaches the gate is below one
 * item's worth. The gate drains one second of backlog per second, so while an item is in transit
 * the backlog ahead of it shrinks by the transit time.
 *
 * <ul>
 *   <li><b>Gate backlog</b> is the GCRA schedule ahead of now ({@link PacedTmdbGate#backlogNanos}),
 *       which already includes artwork and retries.
 *   <li><b>Demand in transit</b> covers items admitted but not yet at the gate (their first
 *       reservation has not happened, so the backlog cannot see them): for every active walk, its
 *       in-transit count times its EWMA of TMDB calls per admitted item, in gate intervals. A
 *       short-circuited item contributes 0 calls, so an unchanged rescan drives this term to 0.
 *   <li><b>One TMDB-bound item's worth</b> is the EWMA of calls per item that reached the gate, in
 *       gate intervals. It is kept apart from the per-admitted-item average on purpose: if both
 *       sides used the same average it would cancel ({@code n < 1 - backlog / (c * interval)} for
 *       any {@code c}), and a rescan would admit one item at a time.
 *   <li><b>No-load transit time</b> is the smallest admission-to-first-reservation time seen for
 *       items that reached the gate (the database lookup before the first TMDB call). Without it at
 *       most one item is in transit while the gate is idle, which caps admission at one item per
 *       transit time and starves the gate whenever the pipeline ahead of it is longer than one
 *       item's worth of gate time. It is the minimum, not an average, because the rest of the
 *       transit time is queueing at the database: crediting queueing would admit more items, which
 *       queue longer, which credits more ({@code n < 1 + n} once transit grows with {@code n}).
 * </ul>
 *
 * <p>Both averages start from 2 calls (search and details) and are learned per workload; the
 * per-admitted-item average restarts from the learned per-TMDB-item value at every walk, so a walk
 * assumes every item needs TMDB until its own items show otherwise. The no-load transit time is
 * unknown (no credit) until an item of the workload reaches the gate, and is kept per workload.
 *
 * <p>Concurrent walks (a scan and a refresh, or two scans) share one arbiter: a fair lock hands
 * the admission turn to waiting walks in arrival order, one item per turn, and every walk is held
 * to the same threshold, the largest over the active walks. Without the turn each walker would
 * poll on its own schedule against its own threshold, and the walk with the larger threshold
 * would take every opening (a refresh would wait for a concurrent scan to finish).
 *
 * <p>The gate backlog is {@link PacedTmdbGate#coreBacklogNanos()}: slots queued by background
 * (secondary artwork) requests are left out, so background artwork cannot hold admission shut.
 *
 * <p>{@code poc.admission.limit} reports the in-transit item count at which admission currently
 * stops: {@code (item's worth + no-load transit - backlog - other walks' demand) / per-item
 * demand}, floored at 0 and capped at {@value #LIMIT_DISPLAY_CEILING} for display only. The Hikari
 * veto is separate and shows in {@code poc.hikari.awaiting}; {@code poc.admission.wait} (tag
 * reason=tmdb|hikari) accumulates the walker's waiting time per reason.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "poc.admission", havingValue = "bottleneck")
public class BottleneckFileAdmission implements FileAdmission, AdmissionRuntime.Listener {

  static final double PRIOR_CALLS_PER_ITEM = 2.0;
  static final double SMOOTHING = 0.1;
  static final double LIMIT_DISPLAY_CEILING = 1_000;
  private static final long MIN_WAIT_NANOS = 200_000L;
  private static final long MAX_WAIT_NANOS = 5_000_000L;

  private final AdmissionRuntime runtime;
  private final PacedTmdbGate gate;
  private final IntSupplier hikariAwaiting;
  private final Map<AdmissionTicket, Walk> walksByTicket = new ConcurrentHashMap<>();
  private final Set<Walk> activeWalks = ConcurrentHashMap.newKeySet();
  private final Map<Workload, Double> learnedCallsPerTmdbItem = new ConcurrentHashMap<>();
  private final Map<Workload, Double> learnedNoLoadTransitNanos = new ConcurrentHashMap<>();
  private final LongAdder tmdbWaitNanos = new LongAdder();
  private final LongAdder hikariWaitNanos = new LongAdder();
  private final ReentrantLock admissionTurn = new ReentrantLock(true);
  private volatile Walk latestWalk;

  @Autowired
  public BottleneckFileAdmission(
      AdmissionRuntime runtime, PacedTmdbGate gate, HikariLive hikari, MeterRegistry registry) {
    this(runtime, gate, hikari::awaiting);
    FunctionCounter.builder("poc.admission.wait", tmdbWaitNanos, w -> w.sum() / 1e6)
        .tag("reason", "tmdb")
        .baseUnit("milliseconds")
        .description("Walker time spent waiting for room at the TMDB gate")
        .register(registry);
    FunctionCounter.builder("poc.admission.wait", hikariWaitNanos, w -> w.sum() / 1e6)
        .tag("reason", "hikari")
        .baseUnit("milliseconds")
        .description("Walker time spent waiting for Hikari to have no waiters")
        .register(registry);
  }

  BottleneckFileAdmission(
      AdmissionRuntime runtime, PacedTmdbGate gate, IntSupplier hikariAwaiting) {
    this.runtime = runtime;
    this.gate = gate;
    this.hikariAwaiting = hikariAwaiting;
    runtime.addListener(this);
    runtime.setAdmissionLimit(this::currentLimitFiles);
  }

  @Override
  public String name() {
    return "bottleneck";
  }

  @Override
  public <T> List<Throwable> processAll(Workload workload, Stream<T> items, AdmittedTask<T> task)
      throws InterruptedException {
    var walk = openWalk(workload);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<Future<TaskOutcome>>();
      var iterator = items.iterator();

      while (iterator.hasNext()) {
        var item = iterator.next();
        var ticket = admitInTurn(walk);
        walk.notePeak(runtime.inFlight());
        tasks.add(executor.submit(() -> ticket.run(task, item)));
      }

      return AdmissionFutures.awaitInOrder(tasks);
    } finally {
      closeWalk(walk);
    }
  }

  @Override
  public void onLeftTransit(AdmissionTicket ticket) {
    var walk = walksByTicket.get(ticket);
    if (walk != null) {
      walk.inTransit.decrementAndGet();
      walk.noteTransit(ticket);
    }
  }

  @Override
  public void onEnded(AdmissionTicket ticket) {
    var walk = walksByTicket.remove(ticket);
    if (walk != null) {
      walk.observe(ticket.tmdbApiReservations() + ticket.tmdbImageReservations());
    }
  }

  private Walk openWalk(Workload workload) {
    var walk =
        new Walk(
            workload,
            learnedCallsPerTmdbItem.getOrDefault(workload, PRIOR_CALLS_PER_ITEM),
            learnedNoLoadTransitNanos.getOrDefault(workload, Double.POSITIVE_INFINITY));
    activeWalks.add(walk);
    latestWalk = walk;
    return walk;
  }

  private void closeWalk(Walk walk) {
    activeWalks.remove(walk);
    learnedCallsPerTmdbItem.put(walk.workload, walk.callsPerTmdbItem);
    learnedNoLoadTransitNanos.put(walk.workload, walk.noLoadTransitNanos);
    log.info(
        "POC bottleneck admission {} finished: admitted={} peakInFlight={} peakLimitFiles={}"
            + " callsPerItem={} callsPerTmdbItem={} waitTmdbMs={} waitHikariMs={}"
            + " transitToGate[n={} minMs={} meanMs={} ewmaMs={} maxMs={}]",
        walk.workload,
        walk.admitted,
        walk.peakInFlight,
        String.format("%.1f", walk.peakLimit),
        String.format("%.3f", walk.callsPerItem),
        String.format("%.3f", walk.callsPerTmdbItem),
        walk.tmdbWaitNanos / 1_000_000,
        walk.hikariWaitNanos / 1_000_000,
        walk.transitCount.sum(),
        String.format("%.1f", walk.noLoadTransitCredit() / 1e6),
        String.format("%.1f", walk.transitNanos.sum() / 1e6 / Math.max(1, walk.transitCount.sum())),
        String.format("%.1f", walk.transitNanosEwma / 1e6),
        String.format("%.1f", walk.transitMaxNanos.get() / 1e6));
  }

  /** Waits for this walk's turn and for room, then admits one item. */
  private AdmissionTicket admitInTurn(Walk walk) throws InterruptedException {
    try {
      admissionTurn.lockInterruptibly();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw exception;
    }

    try {
      awaitRoom(walk);
      var ticket = runtime.admit();
      walk.enter(ticket);
      return ticket;
    } finally {
      admissionTurn.unlock();
    }
  }

  /** One threshold for every active walk: the largest of their own. */
  private double sharedThresholdNanos(double interval) {
    var threshold = 0.0;
    for (var walk : activeWalks) {
      threshold = Math.max(threshold, walk.thresholdNanos(interval));
    }

    return threshold;
  }

  private void awaitRoom(Walk walk) throws InterruptedException {
    while (true) {
      var interval = (double) gate.intervalNanos();
      var threshold = sharedThresholdNanos(interval);
      var demand = gate.coreBacklogNanos() + inTransitDemandNanos(interval);
      var tmdbHasRoom = demand < threshold;
      var hikariHasRoom = hikariAwaiting.getAsInt() <= 0;
      walk.noteLimit(limitFiles(walk, interval));

      if (tmdbHasRoom && hikariHasRoom) {
        return;
      }

      var waitNanos = Math.clamp((long) (demand - threshold), MIN_WAIT_NANOS, MAX_WAIT_NANOS);
      var startedNanos = System.nanoTime();
      sleep(waitNanos);
      var waitedNanos = System.nanoTime() - startedNanos;

      if (tmdbHasRoom) {
        hikariWaitNanos.add(waitedNanos);
        walk.hikariWaitNanos += waitedNanos;
        continue;
      }

      tmdbWaitNanos.add(waitedNanos);
      walk.tmdbWaitNanos += waitedNanos;
    }
  }

  private static void sleep(long nanos) throws InterruptedException {
    try {
      Thread.sleep(Duration.ofNanos(nanos));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw exception;
    }
  }

  private double inTransitDemandNanos(double interval) {
    var calls = 0.0;
    for (var walk : activeWalks) {
      calls += walk.inTransit.get() * walk.callsPerItem;
    }

    return calls * interval;
  }

  private double currentLimitFiles() {
    var walk = latestWalk;
    if (walk == null || !activeWalks.contains(walk)) {
      return Double.NaN;
    }

    return limitFiles(walk, gate.intervalNanos());
  }

  /** In-transit items of {@code walk} at which admission stops, given everything else queued. */
  private double limitFiles(Walk walk, double interval) {
    var othersNanos = inTransitDemandNanos(interval) - walk.inTransit.get() * walk.callsPerItem * interval;
    var roomNanos = sharedThresholdNanos(interval) - gate.coreBacklogNanos() - othersNanos;
    if (roomNanos <= 0) {
      return 0;
    }

    var perItemNanos = walk.callsPerItem * interval;
    if (perItemNanos * LIMIT_DISPLAY_CEILING <= roomNanos) {
      return LIMIT_DISPLAY_CEILING;
    }

    return roomNanos / perItemNanos;
  }

  /** One {@link #processAll} call: its in-transit items and its learned call averages. */
  private final class Walk {

    private final Workload workload;
    private final AtomicInteger inTransit = new AtomicInteger();
    private volatile double callsPerItem;
    private volatile double callsPerTmdbItem;
    private volatile double noLoadTransitNanos;
    private volatile double transitNanosEwma;
    private int admitted;
    private int peakInFlight;
    private double peakLimit;
    private long tmdbWaitNanos;
    private long hikariWaitNanos;
    private final LongAdder transitCount = new LongAdder();
    private final LongAdder transitNanos = new LongAdder();
    private final AtomicLong transitMaxNanos = new AtomicLong();

    private Walk(Workload workload, double callsPerTmdbItem, double noLoadTransitNanos) {
      this.workload = workload;
      this.callsPerTmdbItem = callsPerTmdbItem;
      this.callsPerItem = callsPerTmdbItem;
      this.noLoadTransitNanos = noLoadTransitNanos;
    }

    /** This walk's own admission threshold: one TMDB-bound item's worth plus transit credit. */
    private double thresholdNanos(double interval) {
      return callsPerTmdbItem * interval + noLoadTransitCredit();
    }

    /** 0 until an item of this workload has reached the gate. */
    private double noLoadTransitCredit() {
      var nanos = noLoadTransitNanos;
      return Double.isInfinite(nanos) ? 0 : nanos;
    }

    /** Walker thread only. */
    private void enter(AdmissionTicket ticket) {
      walksByTicket.put(ticket, this);
      inTransit.incrementAndGet();
      admitted++;
    }

    /** Walker thread only. */
    private void notePeak(int inFlight) {
      peakInFlight = Math.max(peakInFlight, inFlight);
    }

    /** Walker thread only. */
    private void noteLimit(double limit) {
      peakLimit = Math.max(peakLimit, limit);
    }

    /** Task threads: admission-to-first-reservation latency of items that reached the gate. */
    private synchronized void noteTransit(AdmissionTicket ticket) {
      if (ticket.ended()) {
        return;
      }

      var nanos = System.nanoTime() - ticket.admittedAtNanos();
      transitCount.increment();
      transitNanos.add(nanos);
      transitMaxNanos.accumulateAndGet(nanos, Math::max);
      transitNanosEwma += SMOOTHING * (nanos - transitNanosEwma);
      noLoadTransitNanos = Math.min(noLoadTransitNanos, nanos);
    }

    /** Task threads: folds one ended item's TMDB reservations into both averages. */
    private synchronized void observe(int calls) {
      callsPerItem += SMOOTHING * (calls - callsPerItem);
      if (calls > 0) {
        callsPerTmdbItem += SMOOTHING * (calls - callsPerTmdbItem);
      }
    }
  }
}
