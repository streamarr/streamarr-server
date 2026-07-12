package com.streamarr.server.services.streaming;

import com.streamarr.transcode.engine.model.TranscodeJobObservation;
import com.streamarr.transcode.engine.model.TranscodeJobRef;

public sealed interface ActiveTranscodeJobInspection
    permits ActiveTranscodeJobInspection.None,
        ActiveTranscodeJobInspection.Observed,
        ActiveTranscodeJobInspection.Unavailable {

  record None() implements ActiveTranscodeJobInspection {}

  record Observed(TranscodeJobObservation observation, int startNumber)
      implements ActiveTranscodeJobInspection {

    public Observed {
      if (observation == null || startNumber < 0) {
        throw new IllegalArgumentException("Observed job values are required");
      }
    }
  }

  record Unavailable(TranscodeJobRef jobRef) implements ActiveTranscodeJobInspection {

    public Unavailable {
      if (jobRef == null) {
        throw new IllegalArgumentException("Unavailable job reference is required");
      }
    }
  }
}
