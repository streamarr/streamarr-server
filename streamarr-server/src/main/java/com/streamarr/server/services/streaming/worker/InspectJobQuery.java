package com.streamarr.server.services.streaming.worker;

import com.streamarr.transcode.engine.model.TranscodeJobRef;

public record InspectJobQuery(WorkerTarget target, TranscodeJobRef jobRef) {

  public InspectJobQuery {
    if (target == null || jobRef == null) {
      throw new IllegalArgumentException("Inspection query values are required");
    }
  }
}
