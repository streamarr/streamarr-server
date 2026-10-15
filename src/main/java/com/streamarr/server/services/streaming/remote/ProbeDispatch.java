package com.streamarr.server.services.streaming.remote;

import java.util.concurrent.Future;
import lombok.NonNull;

/** Whether a worker accepted a probe attempt, and why no worker did otherwise. */
public sealed interface ProbeDispatch {

  record Dispatched(@NonNull Future<ProbeAttemptReport> result) implements ProbeDispatch {}

  record Refused(@NonNull ProbeRefusal reason) implements ProbeDispatch {}
}
