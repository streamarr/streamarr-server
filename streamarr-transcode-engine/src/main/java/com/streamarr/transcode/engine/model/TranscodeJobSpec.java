package com.streamarr.transcode.engine.model;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder
public record TranscodeJobSpec(
    UUID sessionId,
    TranscodeJobRef jobRef,
    MediaSourceRef source,
    TranscodeDecision decision,
    TranscodeExecutionParameters execution,
    List<RenditionSpec> renditions) {

  public TranscodeJobSpec {
    if (sessionId == null
        || jobRef == null
        || source == null
        || decision == null
        || execution == null
        || renditions == null) {
      throw new IllegalArgumentException("Transcode job values are required");
    }
    if (renditions.isEmpty()) {
      throw new IllegalArgumentException("Transcode job requires at least one rendition");
    }
    var labels = new HashSet<String>();
    for (var rendition : renditions) {
      if (!labels.add(rendition.label())) {
        throw new IllegalArgumentException("Transcode job rendition labels must be unique");
      }
    }
    renditions = List.copyOf(renditions);
  }
}
