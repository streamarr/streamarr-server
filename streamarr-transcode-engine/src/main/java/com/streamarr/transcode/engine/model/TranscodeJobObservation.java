package com.streamarr.transcode.engine.model;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import lombok.Builder;

@Builder
public record TranscodeJobObservation(
    TranscodeJobRef jobRef, TranscodeJobState state, List<RenditionObservation> renditions) {

  public TranscodeJobObservation {
    if (jobRef == null || state == null || renditions == null) {
      throw new IllegalArgumentException("Transcode job observation values are required");
    }
    if (renditions.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("Transcode job observation values are required");
    }
    if ((state == TranscodeJobState.ABSENT) != renditions.isEmpty()) {
      throw new IllegalArgumentException("Job presence and rendition snapshot contradict");
    }
    if (!statesAgree(state, renditions)) {
      throw new IllegalArgumentException("Job state and rendition snapshot contradict");
    }
    var labels = new HashSet<String>();
    for (var rendition : renditions) {
      if (!labels.add(rendition.label().toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("Transcode job rendition labels must be unique");
      }
    }
    renditions = List.copyOf(renditions);
  }

  private static boolean statesAgree(
      TranscodeJobState state, List<RenditionObservation> renditions) {
    return switch (state) {
      case ABSENT -> true;
      case ADMITTING ->
          renditions.stream().allMatch(rendition -> rendition.state() == RenditionState.STARTING);
      case RUNNING ->
          renditions.stream().anyMatch(rendition -> rendition.state() == RenditionState.RUNNING)
              && renditions.stream()
                  .allMatch(
                      rendition ->
                          rendition.state() == RenditionState.RUNNING
                              || rendition.state() == RenditionState.COMPLETED);
      case COMPLETED ->
          renditions.stream().allMatch(rendition -> rendition.state() == RenditionState.COMPLETED);
      case FAILED ->
          renditions.stream().anyMatch(rendition -> rendition.state() == RenditionState.FAILED)
              && renditions.stream()
                  .noneMatch(
                      rendition ->
                          rendition.state() == RenditionState.STARTING
                              || rendition.state() == RenditionState.RUNNING);
      case STOPPED ->
          renditions.stream().allMatch(rendition -> rendition.state() == RenditionState.STOPPED);
    };
  }
}
