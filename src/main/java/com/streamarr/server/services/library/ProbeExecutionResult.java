package com.streamarr.server.services.library;

import com.streamarr.server.domain.task.ProbeRequest;
import lombok.NonNull;

/** How one probe execution ends: the instance is done, or it must run again with new inputs. */
public sealed interface ProbeExecutionResult {

  record Completed() implements ProbeExecutionResult {}

  record Rescheduled(@NonNull ProbeRequest request) implements ProbeExecutionResult {}
}
