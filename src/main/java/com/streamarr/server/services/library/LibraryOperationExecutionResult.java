package com.streamarr.server.services.library;

/** How one execution of a library operation stopped. */
public sealed interface LibraryOperationExecutionResult {

  /** The operation ended, or another execution had already ended it. */
  record Ended() implements LibraryOperationExecutionResult {}

  /** A shutdown interrupted the execution; the operation runs again when a scheduler starts. */
  record Interrupted() implements LibraryOperationExecutionResult {}
}
