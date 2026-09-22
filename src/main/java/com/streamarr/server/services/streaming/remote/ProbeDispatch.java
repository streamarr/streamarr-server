package com.streamarr.server.services.streaming.remote;

import com.streamarr.transcode.v1.ProbeAttemptResult;
import java.util.concurrent.Future;
import lombok.NonNull;

/** Whether a worker accepted a probe attempt, and why no worker did otherwise. */
public sealed interface ProbeDispatch {

  record Dispatched(@NonNull Future<ProbeAttemptResult> result) implements ProbeDispatch {}

  record Refused(@NonNull ProbeRefusal reason) implements ProbeDispatch {}
}
