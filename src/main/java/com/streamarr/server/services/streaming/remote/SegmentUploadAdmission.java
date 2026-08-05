package com.streamarr.server.services.streaming.remote;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;

/**
 * Admission control for worker segment uploads: a bounded number of concurrent upload slots, a
 * bounded total of declared segment bytes, and a per-worker allowance within both. {@link
 * #tryAdmit(UUID)} hands out a {@link Ticket} owning exactly one slot and at most one byte
 * reservation; closing the ticket releases whatever it holds and is idempotent, so capacity can
 * never be inflated by a repeated or unpaired release.
 *
 * <p>A ticket is also time-bounded. gRPC imposes no deadline on an inbound stream, so a worker that
 * sends metadata and then stops — its upload loop threw, or its process wedged — would otherwise
 * hold a slot and a full declared-length reservation for as long as the connection survives, and
 * enough such streams can exhaust the configured byte budget. Expired tickets are reclaimed at the
 * moment capacity is needed, which is the only moment the answer changes.
 */
@Slf4j
final class SegmentUploadAdmission {

  private static final Duration DEFAULT_MAXIMUM_UPLOAD_AGE = Duration.ofSeconds(120);

  private final Semaphore uploadSlots;
  private final ByteBudget byteBudget;
  private final int maximumUploadsPerWorker;
  private final Duration maximumUploadAge;
  private final Clock clock;
  private final Map<UUID, Integer> uploadsInFlightByWorker = new ConcurrentHashMap<>();
  private final Set<Ticket> liveTickets = ConcurrentHashMap.newKeySet();

  SegmentUploadAdmission(
      int maximumConcurrentUploads, long maximumBufferedBytes, int maximumUploadsPerWorker) {
    this(
        maximumConcurrentUploads,
        maximumBufferedBytes,
        maximumUploadsPerWorker,
        DEFAULT_MAXIMUM_UPLOAD_AGE,
        Clock.systemUTC());
  }

  SegmentUploadAdmission(
      int maximumConcurrentUploads,
      long maximumBufferedBytes,
      int maximumUploadsPerWorker,
      Duration maximumUploadAge,
      Clock clock) {
    uploadSlots = new Semaphore(maximumConcurrentUploads);
    byteBudget = new ByteBudget(maximumBufferedBytes);
    this.maximumUploadsPerWorker = maximumUploadsPerWorker;
    this.maximumUploadAge = maximumUploadAge;
    this.clock = clock;
  }

  /**
   * One admitted upload for {@code workerId}, or empty when either that worker's allowance or the
   * fleet-wide concurrent-upload limit is reached.
   */
  Optional<Ticket> tryAdmit(UUID workerId) {
    reclaimExpired();

    if (!tryClaimWorkerAllowance(workerId)) {
      return Optional.empty();
    }
    if (!uploadSlots.tryAcquire()) {
      releaseWorkerAllowance(workerId);
      return Optional.empty();
    }

    var ticket = new Ticket(workerId, clock.instant());
    liveTickets.add(ticket);
    return Optional.of(ticket);
  }

  private void reclaimExpired() {
    var cutoff = clock.instant().minus(maximumUploadAge);
    for (var ticket : liveTickets) {
      if (ticket.admittedAt().isBefore(cutoff)) {
        log.warn(
            "Reclaiming segment upload from worker {} that exceeded {}",
            ticket.workerId(),
            maximumUploadAge);
        ticket.reclaim();
      }
    }
  }

  private boolean tryClaimWorkerAllowance(UUID workerId) {
    var claimed = new boolean[1];
    uploadsInFlightByWorker.compute(
        workerId,
        (_, inFlight) -> {
          var current = inFlight == null ? 0 : inFlight;
          if (current >= maximumUploadsPerWorker) {
            return current;
          }
          claimed[0] = true;
          return current + 1;
        });
    return claimed[0];
  }

  private void releaseWorkerAllowance(UUID workerId) {
    uploadsInFlightByWorker.computeIfPresent(
        workerId, (_, inFlight) -> inFlight <= 1 ? null : inFlight - 1);
  }

  private static final class ByteBudget {

    private final long maximumBytes;
    private long reservedBytes;

    private ByteBudget(long maximumBytes) {
      this.maximumBytes = maximumBytes;
    }

    private synchronized boolean tryReserve(long bytes) {
      if (reservedBytes > maximumBytes - bytes) {
        return false;
      }
      reservedBytes += bytes;
      return true;
    }

    private synchronized void release(long bytes) {
      reservedBytes -= bytes;
    }
  }

  /** Owns one upload slot and at most one byte reservation; close releases both exactly once. */
  final class Ticket implements AutoCloseable {

    private final UUID workerId;
    private final Instant admittedAt;
    private long heldBytes;
    private boolean closed;
    private boolean reclaimed;
    private Runnable reclaimHandler;

    private Ticket(UUID workerId, Instant admittedAt) {
      this.workerId = workerId;
      this.admittedAt = admittedAt;
    }

    private UUID workerId() {
      return workerId;
    }

    private Instant admittedAt() {
      return admittedAt;
    }

    /** Whether this ticket no longer holds capacity — closed by its owner or reclaimed by age. */
    synchronized boolean isClosed() {
      return closed;
    }

    synchronized boolean tryReserve(long declaredBytes) {
      if (declaredBytes <= 0) {
        throw new IllegalArgumentException("declaredBytes must be positive, got " + declaredBytes);
      }
      if (closed || heldBytes != 0) {
        throw new IllegalStateException("Ticket is closed or already holds a reservation");
      }
      if (!byteBudget.tryReserve(declaredBytes)) {
        return false;
      }

      heldBytes = declaredBytes;
      return true;
    }

    void onReclaimed(Runnable handler) {
      boolean runNow;
      synchronized (this) {
        if (reclaimHandler != null) {
          throw new IllegalStateException("Ticket already has a reclaim handler");
        }
        reclaimHandler = handler;
        runNow = reclaimed;
      }
      if (runNow) {
        handler.run();
      }
    }

    private void reclaim() {
      Runnable handler;
      synchronized (this) {
        if (closed) {
          return;
        }
        reclaimed = true;
        handler = reclaimHandler;
        close();
      }
      if (handler != null) {
        handler.run();
      }
    }

    @Override
    public synchronized void close() {
      if (closed) {
        return;
      }

      closed = true;
      byteBudget.release(heldBytes);
      heldBytes = 0;
      uploadSlots.release();
      releaseWorkerAllowance(workerId);
      liveTickets.remove(this);
    }
  }
}
