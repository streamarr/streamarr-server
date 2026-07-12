package com.streamarr.server.services.streaming.worker;

import com.streamarr.transcode.engine.model.TranscodeJobRef;

public sealed interface StopJobResult
    permits StopJobResult.Stopped,
        StopJobResult.AlreadyAbsent,
        StopJobResult.CleanupPending,
        StopJobResult.Rejected {

  record Stopped(TranscodeJobRef jobRef) implements StopJobResult {

    public Stopped {
      requireValue(jobRef, "Stopped job reference is required");
    }
  }

  record AlreadyAbsent(TranscodeJobRef jobRef) implements StopJobResult {

    public AlreadyAbsent {
      requireValue(jobRef, "Absent job reference is required");
    }
  }

  record CleanupPending(TranscodeJobRef jobRef) implements StopJobResult {

    public CleanupPending {
      requireValue(jobRef, "Cleanup-pending job reference is required");
    }
  }

  record Rejected(StopJobRejection reason) implements StopJobResult {

    public Rejected {
      requireValue(reason, "Stop rejection reason is required");
    }
  }

  private static void requireValue(Object value, String message) {
    if (value == null) {
      throw new IllegalArgumentException(message);
    }
  }
}
