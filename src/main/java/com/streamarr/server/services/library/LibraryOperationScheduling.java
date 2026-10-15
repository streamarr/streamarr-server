package com.streamarr.server.services.library;

import com.streamarr.server.exceptions.LibraryOperationNotScheduledException;
import java.util.UUID;

/** Schedules the execution of a stored library operation. */
public interface LibraryOperationScheduling {

  /**
   * Schedules the operation's one scheduled task to run now, in the caller's transaction, so the
   * task and the operation commit or roll back together.
   *
   * @throws LibraryOperationNotScheduledException when the task cannot be stored
   */
  void scheduleExecution(UUID libraryOperationId);
}
