package com.streamarr.server.services.streaming.worker;

import com.streamarr.transcode.engine.model.TranscodeJobObservation;
import com.streamarr.transcode.engine.model.TranscodeJobRef;

public sealed interface InspectJobResult
    permits InspectJobResult.Observed, InspectJobResult.CleanupPending, InspectJobResult.Rejected {

  record Observed(TranscodeJobObservation observation) implements InspectJobResult {

    public Observed {
      requireValue(observation, "Inspection observation is required");
    }
  }

  record CleanupPending(TranscodeJobRef jobRef) implements InspectJobResult {

    public CleanupPending {
      requireValue(jobRef, "Cleanup-pending job reference is required");
    }
  }

  record Rejected(InspectJobRejection reason) implements InspectJobResult {

    public Rejected {
      requireValue(reason, "Inspection rejection reason is required");
    }
  }

  private static void requireValue(Object value, String message) {
    if (value == null) {
      throw new IllegalArgumentException(message);
    }
  }
}
