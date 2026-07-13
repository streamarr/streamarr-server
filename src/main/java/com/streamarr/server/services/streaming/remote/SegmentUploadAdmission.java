package com.streamarr.server.services.streaming.remote;

import java.util.concurrent.Semaphore;

final class SegmentUploadAdmission {

  private final Semaphore uploadSlots;
  private final long maximumBufferedBytes;
  private long reservedBytes;

  SegmentUploadAdmission(int maximumConcurrentUploads, long maximumBufferedBytes) {
    uploadSlots = new Semaphore(maximumConcurrentUploads);
    this.maximumBufferedBytes = maximumBufferedBytes;
  }

  boolean tryOpen() {
    return uploadSlots.tryAcquire();
  }

  synchronized boolean tryReserve(long bytes) {
    if (reservedBytes > maximumBufferedBytes - bytes) {
      return false;
    }
    reservedBytes += bytes;
    return true;
  }

  void release(long bytes) {
    synchronized (this) {
      reservedBytes -= bytes;
    }
    uploadSlots.release();
  }
}
