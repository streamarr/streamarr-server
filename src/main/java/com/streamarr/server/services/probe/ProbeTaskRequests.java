package com.streamarr.server.services.probe;

import com.streamarr.server.domain.task.ProbeTaskRequest;

/** Records that a media file needs probing at the observed snapshot and version. */
public interface ProbeTaskRequests {

  void request(ProbeTaskRequest request);
}
