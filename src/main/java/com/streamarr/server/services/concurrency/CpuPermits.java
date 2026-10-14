package com.streamarr.server.services.concurrency;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import lombok.NonNull;

/**
 * Bounds how much CPU-heavy work, such as decoding and resizing images, runs at once. A freed
 * permit goes to primary work first: background work gets a permit only while no primary work waits
 * for one, so background work never delays pending primary work.
 */
public final class CpuPermits {

  /** Primary work always gets a free permit before background work. */
  public enum Priority {
    PRIMARY,
    BACKGROUND
  }

  /** Work that runs while it holds a permit. */
  @FunctionalInterface
  public interface Work<T, E extends Exception> {
    T run() throws E;
  }

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition permitFreed = lock.newCondition();
  private int availablePermits;
  private int waitingPrimaryWork;

  public CpuPermits(int permits) {
    if (permits < 1) {
      throw new IllegalArgumentException("CPU permits must be at least 1, was " + permits);
    }

    this.availablePermits = permits;
  }

  /**
   * Waits for a permit, runs the work while holding it, and releases it when the work ends.
   *
   * @throws InterruptedException if the thread is interrupted while it waits for a permit
   */
  public <T, E extends Exception> T runWithPermit(@NonNull Priority priority, Work<T, E> work)
      throws E, InterruptedException {
    acquire(priority);
    try {
      return work.run();
    } finally {
      release();
    }
  }

  private void acquire(Priority priority) throws InterruptedException {
    lock.lockInterruptibly();
    try {
      switch (priority) {
        case PRIMARY -> acquirePrimary();
        case BACKGROUND -> acquireBackground();
      }
    } finally {
      lock.unlock();
    }
  }

  private void acquirePrimary() throws InterruptedException {
    waitingPrimaryWork++;
    try {
      while (availablePermits == 0) {
        permitFreed.await();
      }

      availablePermits--;
    } finally {
      waitingPrimaryWork--;
      // Background waiters check again once no primary work waits.
      permitFreed.signalAll();
    }
  }

  private void acquireBackground() throws InterruptedException {
    while (availablePermits == 0 || waitingPrimaryWork > 0) {
      permitFreed.await();
    }

    availablePermits--;
  }

  private void release() {
    lock.lock();
    try {
      availablePermits++;
      permitFreed.signalAll();
    } finally {
      lock.unlock();
    }
  }
}
