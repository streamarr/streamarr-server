package com.streamarr.server.services.streaming.remote;

import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * Admission control for worker segment uploads: a bounded number of concurrent upload slots and a
 * bounded total of declared segment bytes. {@link #tryAdmit()} hands out a {@link Ticket} owning
 * exactly one slot and at most one byte reservation; closing the ticket releases whatever it holds
 * and is idempotent, so capacity can never be inflated by a repeated or unpaired release.
 */
final class SegmentUploadAdmission {

  private final Semaphore uploadSlots;
  private final ByteBudget byteBudget;

  SegmentUploadAdmission(int maximumConcurrentUploads, long maximumBufferedBytes) {
    uploadSlots = new Semaphore(maximumConcurrentUploads);
    byteBudget = new ByteBudget(maximumBufferedBytes);
  }

  /** One admitted upload, or empty when the concurrent-upload limit is reached. */
  Optional<Ticket> tryAdmit() {
    if (!uploadSlots.tryAcquire()) {
      return Optional.empty();
    }

    return Optional.of(new Ticket());
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

    private long heldBytes;
    private boolean closed;

    private Ticket() {}

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

    @Override
    public synchronized void close() {
      if (closed) {
        return;
      }

      closed = true;
      byteBudget.release(heldBytes);
      heldBytes = 0;
      uploadSlots.release();
    }
  }
}
