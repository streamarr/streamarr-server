package com.streamarr.server.services.streaming.local;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.streaming.CommittedStreamSessionTimeline;
import com.streamarr.server.services.streaming.RuntimeSessionReservation;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.services.streaming.RuntimeTranscodeStart;
import com.streamarr.transcode.engine.model.TranscodeJobRef;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class InMemoryStreamSessionRepository implements RuntimeStreamSessionRegistry {

  private enum State {
    STARTING,
    RUNNING,
    TERMINAL
  }

  private static final class Slot {

    private final UUID generation = UUID.randomUUID();
    private volatile State state = State.STARTING;
    private Instant latestTimelineAccessedAt = Instant.MIN;
    private final AtomicReference<StreamSession> session = new AtomicReference<>();
    private final LinkedHashSet<TranscodeJobRef> jobRefs = new LinkedHashSet<>();
    private final LinkedHashSet<TranscodeJobRef> startingJobRefs = new LinkedHashSet<>();
    private final LinkedHashSet<TranscodeJobRef> releasedJobRefs = new LinkedHashSet<>();
    private TranscodeJobRef activeJobRef;
    private long highestAcceptedJobGeneration;
    private int inFlight;
    private int starters;
    private final AtomicReference<CompletableFuture<Void>> startersDrained =
        new AtomicReference<>(CompletableFuture.completedFuture(null));
    private boolean runtimeStopped = true;
    private boolean reclaimRequested;

    private Slot(int inFlight) {
      this.inFlight = inFlight;
    }
  }

  private final ConcurrentHashMap<UUID, Slot> slots = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Long> highestIssuedGenerationByJobId =
      new ConcurrentHashMap<>();
  private final Object reservationMonitor = new Object();
  private boolean acceptingReservations = true;

  @Override
  public Optional<RuntimeSessionReservation> reserve(UUID sessionId) {
    synchronized (reservationMonitor) {
      if (!acceptingReservations) {
        return Optional.empty();
      }
      var reservation = new AtomicReference<RuntimeSessionReservation>();
      slots.compute(
          sessionId,
          (_, existing) -> {
            if (existing != null) {
              return existing;
            }
            var created = new Slot(1);
            reservation.set(new RuntimeSessionReservation(sessionId, created.generation));
            return created;
          });
      return Optional.ofNullable(reservation.get());
    }
  }

  @Override
  public boolean attach(RuntimeSessionReservation reservation, StreamSession session) {
    var attached = new AtomicBoolean();
    slots.computeIfPresent(
        reservation.sessionId(),
        (_, slot) -> {
          if (matches(slot, reservation.generation()) && slot.state != State.TERMINAL) {
            slot.session.set(session);
            attached.set(true);
          }
          return slot;
        });
    return attached.get();
  }

  @Override
  public Optional<RuntimeTranscodeStart> beginTranscodeStart(UUID sessionId) {
    var start = new AtomicReference<RuntimeTranscodeStart>();
    slots.computeIfPresent(
        sessionId,
        (_, slot) -> {
          if (slot.state != State.TERMINAL) {
            if (slot.starters == 0) {
              slot.startersDrained.set(new CompletableFuture<>());
            }
            slot.inFlight++;
            slot.starters++;
            var jobGeneration =
                highestIssuedGenerationByJobId.compute(
                    sessionId, (_, current) -> current == null ? 1L : Math.incrementExact(current));
            var jobRef = new TranscodeJobRef(sessionId, jobGeneration);
            slot.jobRefs.add(jobRef);
            slot.startingJobRefs.add(jobRef);
            slot.runtimeStopped = false;
            start.set(new RuntimeTranscodeStart(sessionId, slot.generation, jobRef));
          }
          return slot;
        });
    return Optional.ofNullable(start.get());
  }

  @Override
  public boolean completeTranscodeStart(RuntimeTranscodeStart start) {
    var accepted = new AtomicBoolean();
    slots.computeIfPresent(
        start.sessionId(),
        (_, slot) -> {
          if (!matches(slot, start.slotGeneration())) {
            return slot;
          }
          if (slot.state == State.TERMINAL
              || !slot.startingJobRefs.contains(start.jobRef())
              || slot.releasedJobRefs.contains(start.jobRef())) {
            return slot;
          }
          finishStarter(slot, start.jobRef());
          if (start.jobRef().generation() > slot.highestAcceptedJobGeneration) {
            slot.activeJobRef = start.jobRef();
            slot.highestAcceptedJobGeneration = start.jobRef().generation();
          }
          accepted.set(true);
          return slot;
        });
    return accepted.get();
  }

  @Override
  public void abortTranscodeStart(RuntimeTranscodeStart start) {
    finishStart(start, false);
  }

  @Override
  public void finishRejectedTranscodeStart(RuntimeTranscodeStart start, boolean stopped) {
    finishStart(start, stopped);
  }

  @Override
  public List<TranscodeJobRef> snapshotTranscodeJobRefs(UUID sessionId) {
    var snapshot = new AtomicReference<List<TranscodeJobRef>>(List.of());
    slots.computeIfPresent(
        sessionId,
        (_, slot) -> {
          snapshot.set(List.copyOf(slot.jobRefs));
          return slot;
        });
    return snapshot.get();
  }

  @Override
  public Optional<TranscodeJobRef> activeTranscodeJobRef(UUID sessionId) {
    var active = new AtomicReference<TranscodeJobRef>();
    slots.computeIfPresent(
        sessionId,
        (_, slot) -> {
          active.set(slot.activeJobRef);
          return slot;
        });
    return Optional.ofNullable(active.get());
  }

  @Override
  public void markTranscodeJobReleased(TranscodeJobRef jobRef) {
    slots.computeIfPresent(
        jobRef.jobId(),
        (_, slot) -> {
          if (slot.startingJobRefs.contains(jobRef)) {
            slot.releasedJobRefs.add(jobRef);
            return slot;
          }
          slot.jobRefs.remove(jobRef);
          slot.releasedJobRefs.remove(jobRef);
          if (jobRef.equals(slot.activeJobRef)) {
            slot.activeJobRef = null;
          }
          slot.runtimeStopped = slot.jobRefs.isEmpty();
          return reclaimable(slot) ? null : slot;
        });
  }

  private void finishStart(RuntimeTranscodeStart start, boolean stopped) {
    slots.computeIfPresent(
        start.sessionId(),
        (_, slot) -> {
          if (!matches(slot, start.slotGeneration())) {
            return slot;
          }
          if (!finishStarter(slot, start.jobRef())) {
            return slot;
          }
          if (stopped || slot.releasedJobRefs.remove(start.jobRef())) {
            slot.jobRefs.remove(start.jobRef());
          }
          slot.runtimeStopped = slot.jobRefs.isEmpty();
          return reclaimable(slot) ? null : slot;
        });
  }

  @Override
  public boolean markRunning(RuntimeSessionReservation reservation) {
    var running = new AtomicBoolean();
    slots.computeIfPresent(
        reservation.sessionId(),
        (_, slot) -> {
          if (matches(slot, reservation.generation()) && slot.state != State.TERMINAL) {
            slot.state = State.RUNNING;
            running.set(true);
          }
          return slot;
        });
    return running.get();
  }

  @Override
  public void releaseReservation(RuntimeSessionReservation reservation) {
    slots.computeIfPresent(
        reservation.sessionId(),
        (_, slot) -> {
          if (!matches(slot, reservation.generation())) {
            return slot;
          }
          slot.inFlight--;
          return reclaimable(slot) ? null : slot;
        });
  }

  @Override
  public boolean terminalize(UUID sessionId) {
    var found = new AtomicBoolean();
    slots.computeIfPresent(
        sessionId,
        (_, slot) -> {
          found.set(true);
          slot.state = State.TERMINAL;
          slot.session.set(null);
          return slot;
        });
    return found.get();
  }

  @Override
  public void markRuntimeStopped(UUID sessionId) {
    slots.computeIfPresent(
        sessionId,
        (_, slot) -> {
          slot.jobRefs.removeIf(jobRef -> !slot.startingJobRefs.contains(jobRef));
          slot.releasedJobRefs.retainAll(slot.jobRefs);
          slot.activeJobRef = null;
          slot.runtimeStopped = slot.jobRefs.isEmpty();
          return reclaimable(slot) ? null : slot;
        });
  }

  @Override
  public boolean releaseTerminal(UUID sessionId) {
    var quiescent = new AtomicBoolean(true);
    slots.computeIfPresent(
        sessionId,
        (_, slot) -> {
          slot.reclaimRequested = true;
          quiescent.set(slot.starters == 0 && slot.runtimeStopped);
          return reclaimable(slot) ? null : slot;
        });
    return quiescent.get();
  }

  @Override
  public void awaitTranscodeStarts(UUID sessionId) {
    var slot = slots.get(sessionId);
    if (slot != null) {
      slot.startersDrained.get().join();
    }
  }

  @Override
  public Collection<UUID> fenceAll() {
    synchronized (reservationMonitor) {
      acceptingReservations = false;
      var sessionIds = List.copyOf(slots.keySet());
      sessionIds.forEach(this::terminalize);
      return sessionIds;
    }
  }

  @Override
  public Collection<UUID> snapshotCleanupCandidateIds() {
    return List.copyOf(
        slots.entrySet().stream()
            .filter(entry -> cleanupCandidate(entry.getValue()))
            .map(java.util.Map.Entry::getKey)
            .toList());
  }

  @Override
  public void mirrorCommittedAccess(UUID sessionId, Instant accessedAt) {
    slots.computeIfPresent(
        sessionId,
        (_, slot) -> {
          var session = slot.session.get();
          if (session != null && accessedAt.isAfter(session.getLastAccessedAt())) {
            session.setLastAccessedAt(accessedAt);
          }
          return slot;
        });
  }

  @Override
  public void mirrorCommittedTimeline(CommittedStreamSessionTimeline timeline) {
    slots.computeIfPresent(
        timeline.streamSessionId(),
        (_, slot) -> {
          var session = slot.session.get();
          if (session == null || timeline.accessedAt().isBefore(slot.latestTimelineAccessedAt)) {
            return slot;
          }
          session.mirrorCommittedPlaybackState(
              timeline.positionSeconds(), timeline.state(), timeline.accessedAt());
          slot.latestTimelineAccessedAt = timeline.accessedAt();
          return slot;
        });
  }

  @Override
  public void save(StreamSession session) {
    slots.compute(
        session.getSessionId(),
        (_, slot) -> {
          if (slot == null) {
            slot = new Slot(0);
            slot.state = State.RUNNING;
          }
          if (slot.state != State.TERMINAL) {
            slot.session.set(session);
          }
          return slot;
        });
  }

  @Override
  public Optional<StreamSession> findById(UUID sessionId) {
    var slot = slots.get(sessionId);
    if (slot == null || slot.state == State.TERMINAL) {
      return Optional.empty();
    }
    return Optional.ofNullable(slot.session.get());
  }

  @Override
  public Optional<StreamSession> removeById(UUID sessionId) {
    var removed = new AtomicReference<StreamSession>();
    slots.computeIfPresent(
        sessionId,
        (_, slot) -> {
          removed.set(slot.session.getAndSet(null));
          slot.state = State.TERMINAL;
          slot.runtimeStopped = slot.jobRefs.isEmpty();
          slot.reclaimRequested = true;
          return reclaimable(slot) ? null : slot;
        });
    return Optional.ofNullable(removed.get());
  }

  @Override
  public Collection<StreamSession> findAll() {
    return Collections.unmodifiableList(
        slots.values().stream()
            .filter(slot -> slot.state != State.TERMINAL)
            .map(slot -> slot.session.get())
            .filter(java.util.Objects::nonNull)
            .toList());
  }

  @Override
  public int count() {
    return findAll().size();
  }

  private static boolean matches(Slot slot, UUID generation) {
    return slot.generation.equals(generation);
  }

  private static boolean cleanupCandidate(Slot slot) {
    return slot.session.get() != null || (slot.state == State.TERMINAL && !slot.runtimeStopped);
  }

  private static boolean finishStarter(Slot slot, TranscodeJobRef jobRef) {
    if (!slot.startingJobRefs.remove(jobRef)) {
      return false;
    }
    slot.inFlight--;
    slot.starters--;
    if (slot.starters == 0) {
      slot.startersDrained.get().complete(null);
    }
    return true;
  }

  private static boolean reclaimable(Slot slot) {
    return slot.state == State.TERMINAL
        && slot.inFlight == 0
        && slot.runtimeStopped
        && slot.reclaimRequested;
  }
}
