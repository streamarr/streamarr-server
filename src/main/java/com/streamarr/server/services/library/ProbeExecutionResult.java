package com.streamarr.server.services.library;

import com.streamarr.server.domain.task.ProbeTaskRequest;
import lombok.NonNull;

/**
 * How one probe execution ends: the instance is done, it must run again with new inputs, its source
 * changed and it must run again once the source is quiet, or it waits for a free worker slot
 * without counting as a failure.
 */
public sealed interface ProbeExecutionResult {

  record Completed() implements ProbeExecutionResult {}

  record Rescheduled(@NonNull ProbeTaskRequest request) implements ProbeExecutionResult {}

  record SourceChanged(@NonNull ProbeTaskRequest request) implements ProbeExecutionResult {}

  record Deferred() implements ProbeExecutionResult {}
}
