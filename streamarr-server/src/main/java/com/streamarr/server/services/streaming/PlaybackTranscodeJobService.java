package com.streamarr.server.services.streaming;

import com.streamarr.transcode.engine.model.TranscodeJobObservation;
import java.util.UUID;

public interface PlaybackTranscodeJobService {

  TranscodeJobObservation start(StartPlaybackTranscodeJobCommand command);

  ActiveTranscodeJobInspection inspectActive(UUID sessionId);

  RuntimeTranscodeCleanup suspend(UUID sessionId);

  RuntimeTranscodeCleanup cleanupTerminal(UUID sessionId);
}
