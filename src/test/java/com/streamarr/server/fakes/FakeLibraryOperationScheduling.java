package com.streamarr.server.fakes;

import com.streamarr.server.exceptions.LibraryOperationNotScheduledException;
import com.streamarr.server.services.library.LibraryOperationScheduling;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps the ids of scheduled library operations; a test runs their executions itself. */
public class FakeLibraryOperationScheduling implements LibraryOperationScheduling {

  private final List<UUID> scheduled = new CopyOnWriteArrayList<>();
  private final AtomicReference<LibraryOperationNotScheduledException> nextFailure =
      new AtomicReference<>();

  /** Makes the next call throw {@code failure}, as a failed insert of the task would. */
  public void failNextWith(LibraryOperationNotScheduledException failure) {
    nextFailure.set(failure);
  }

  @Override
  public void scheduleExecution(UUID libraryOperationId) {
    var failure = nextFailure.getAndSet(null);
    if (failure != null) {
      throw failure;
    }

    scheduled.add(libraryOperationId);
  }

  public List<UUID> scheduledExecutions() {
    return List.copyOf(scheduled);
  }
}
