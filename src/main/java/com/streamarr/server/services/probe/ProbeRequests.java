package com.streamarr.server.services.probe;

import com.streamarr.server.domain.task.ProbeRequest;

/** Records that a media file needs probing at the observed snapshot and version. */
public interface ProbeRequests {

  void request(ProbeRequest request);
}
