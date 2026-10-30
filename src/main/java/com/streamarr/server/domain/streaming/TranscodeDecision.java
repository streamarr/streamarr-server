package com.streamarr.server.domain.streaming;

import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * {@code videoSource} holds the playback facts of the chosen video stream that the decision read. A
 * decision that copies the video needs the codec string that the probe stored, because the copy job
 * carries it.
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
        && videoSource.flatMap(VideoPlaybackFacts::codecString).isEmpty()) {
      throw new IllegalArgumentException(
          "A decision that copies the video needs the source's codec string");
    }
  }

  /** The output frame rate of the video source that the decision read, empty without one. */
  public Optional<Fraction> outputFrameRate() {
    return videoSource.flatMap(VideoPlaybackFacts::outputFrameRate);
  }

  // Lombok builder default — its generated setter assigns it
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class TranscodeDecisionBuilder {
    private Optional<VideoPlaybackFacts> videoSource = Optional.empty();
  }
}
