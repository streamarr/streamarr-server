package com.streamarr.transcode.engine.model;

import java.util.HashSet;
import java.util.List;
import lombok.Builder;

@Builder
public record TranscodeJobObservation(
    TranscodeJobRef jobRef, TranscodeJobState state, List<RenditionObservation> renditions) {

  public TranscodeJobObservation {
    if (jobRef == null || state == null || renditions == null) {
      throw new IllegalArgumentException("Transcode job observation values are required");
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
