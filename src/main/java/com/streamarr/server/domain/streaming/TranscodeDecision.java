package com.streamarr.server.domain.streaming;

import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * {@code videoSource} holds the playback facts of the chosen video stream that the decision read,
 * and is empty when the probe version reports none. A decision that copies a stream with playback
 * facts needs the codec string that the probe stored, because the copy carries it.
 */
@Builder
public record TranscodeDecision(
    TranscodeMode transcodeMode,
    String videoCodecFamily,
    AudioDecision audioDecision,
    SubtitleDecision subtitleDecision,
    @NonNull Optional<VideoPlaybackFacts> videoSource) {

  public TranscodeDecision {
    if (transcodeMode != null
        && transcodeMode.copiesVideo()
        && videoSource.filter(facts -> facts.codecString().isEmpty()).isPresent()) {
      throw new IllegalArgumentException(
          "A decision that copies the video needs the source's codec string");
    }
  }

  @SuppressWarnings("java:S1068") // Lombok builder defaults — fields are used by generated code
  public static class TranscodeDecisionBuilder {
    private Optional<VideoPlaybackFacts> videoSource = Optional.empty();
  }
}
