package com.streamarr.server.services.streaming;

import com.streamarr.transcode.engine.model.MediaSourceRef;
import com.streamarr.transcode.engine.model.RenditionSpec;
import com.streamarr.transcode.engine.model.TranscodeDecision;
import com.streamarr.transcode.engine.model.TranscodeExecutionParameters;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder
public record StartPlaybackTranscodeJobCommand(
    UUID sessionId,
    MediaSourceRef source,
    TranscodeDecision decision,
    TranscodeExecutionParameters execution,
    List<RenditionSpec> renditions) {

  public StartPlaybackTranscodeJobCommand {
    if (sessionId == null
        || source == null
        || decision == null
        || execution == null
        || renditions == null) {
      throw new IllegalArgumentException("Playback transcode job values are required");
    }
    if (renditions.isEmpty() || renditions.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("Playback transcode job requires renditions");
    }
    renditions = List.copyOf(renditions);
  }
}
