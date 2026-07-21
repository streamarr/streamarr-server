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
  private final long maximumBufferedBytes;
  private long reservedBytes;

  SegmentUploadAdmission(int maximumConcurrentUploads, long maximumBufferedBytes) {
    uploadSlots = new Semaphore(maximumConcurrentUploads);
    this.maximumBufferedBytes = maximumBufferedBytes;
  }

  /** One admitted upload, or empty when the concurrent-upload limit is reached. */
  Optional<Ticket> tryAdmit() {
    if (!uploadSlots.tryAcquire()) {
      return Optional.empty();
    }

    return Optional.of(new Ticket());
  }

  /** Owns one upload slot and at most one byte reservation; close releases both exactly once. */
  final class Ticket implements AutoCloseable {

    private long heldBytes;
    private boolean closed;

    private Ticket() {}

    /** Reserves the declared segment length against the shared byte budget. */
    synchronized boolean tryReserve(long bytes) {
      if (bytes <= 0) {
        throw new IllegalArgumentException("bytes must be positive, got " + bytes);
      }
      if (closed || heldBytes != 0) {
        throw new IllegalStateException("Ticket is closed or already holds a reservation");
      }
      // The byte budget is shared admission state; its monitor is taken after the ticket's,
      // and nothing takes them in the opposite order.
      synchronized (SegmentUploadAdmission.this) {
        if (reservedBytes > maximumBufferedBytes - bytes) {
          return false;
        }
        reservedBytes += bytes;
      }

      heldBytes = bytes;
      return true;
    }

    @Override
    public synchronized void close() {
      if (closed) {
        return;
      }

      closed = true;
      synchronized (SegmentUploadAdmission.this) {
        reservedBytes -= heldBytes;
      }
      heldBytes = 0;
      uploadSlots.release();
    }
  }
}
