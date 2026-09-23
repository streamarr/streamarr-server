package com.streamarr.server.services.probe;

import com.streamarr.server.domain.task.ProbeTaskRequest;

/** Records that a media file needs probing at the observed snapshot and version. */
public interface ProbeTaskRequests {

  void request(ProbeTaskRequest request);

  /**
   * Requests the probe like {@link #request}, and runs a retry that waits out the backoff of a
   * failed attempt at once. A pending probe that waits for any other reason keeps its time.
   */
  void requestRetryingFailure(ProbeTaskRequest request);
}
