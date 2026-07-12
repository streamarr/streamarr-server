package com.streamarr.server.services.streaming.worker;

import com.streamarr.transcode.engine.model.TranscodeJobObservation;
import com.streamarr.transcode.engine.model.TranscodeJobRef;

public sealed interface StartJobResult
    permits StartJobResult.Accepted, StartJobResult.Rejected, StartJobResult.CleanupPending {

  record Accepted(TranscodeJobObservation observation) implements StartJobResult {

    public Accepted {
      requireValue(observation, "Start observation is required");
    }
  }

  record Rejected(StartJobRejection reason) implements StartJobResult {

    public Rejected {
      requireValue(reason, "Start rejection reason is required");
    }
  }

  record CleanupPending(TranscodeJobRef jobRef) implements StartJobResult {

    public CleanupPending {
      requireValue(jobRef, "Cleanup-pending job reference is required");
    }
  }

  private static void requireValue(Object value, String message) {
    if (value == null) {
      throw new IllegalArgumentException(message);
    }
  }
}
