package com.streamarr.server.services.library.admission;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** Joins submitted tasks the way the scan always has. */
public final class AdmissionFutures {

  private AdmissionFutures() {}

  /**
   * Waits for each task in submission order and returns the failures in that order. On interrupt
   * the flag is re-asserted before throwing, so a surrounding executor's {@code close()} cancels
   * the remaining tasks, as the scan's join did.
   */
  public static List<Throwable> awaitInOrder(List<? extends Future<?>> tasks)
      throws InterruptedException {
    var failures = new ArrayList<Throwable>();

    for (var task : tasks) {
      try {
        task.get();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw exception;
      } catch (ExecutionException exception) {
        failures.add(exception.getCause());
      }
    }

    return failures;
  }
}
