package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.exceptions.TranscodeException;
import java.nio.file.Path;

public interface FfprobeService {

  /** Returns a complete success or terminal media error. Execution failures remain retryable. */
  ProbeOutcome probe(Path filepath);

  default MediaProbe probeMedia(Path filepath) {
    return switch (probe(filepath)) {
      case ProbeOutcome.Success success -> success.mediaProbe();
      case ProbeOutcome.Failure(_) ->
          throw new TranscodeException(TranscodeException.GENERIC_MESSAGE);
    };
  }
}
