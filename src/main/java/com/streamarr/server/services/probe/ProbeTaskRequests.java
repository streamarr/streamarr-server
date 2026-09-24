package com.streamarr.server.services.probe;

import com.streamarr.server.domain.task.ProbeTaskRequest;

/** Records that a media file needs probing at the observed snapshot and version. */
public interface ProbeTaskRequests {

  void request(ProbeTaskRequest request);

  /**
   * Requests the probe like {@link #request}, and runs it at once when the last attempt failed with
   * a saved reason, so its backoff starts again. A pending probe whose last attempt saved no
   * failure keeps its time.
   */
  void requestRetryingFailure(ProbeTaskRequest request);
}
