package com.streamarr.server.domain.streaming;

import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * {@code encodeCodecProfile} holds the codec profile that the decision's encode forces (ADR 0013),
 * so a decision that copies the video holds none. {@code videoSource} holds the playback facts of
 * the chosen video stream that the decision read. A decision that copies the video needs the codec
 * string that the probe stored, because the copy job carries it.
 */
@Builder(toBuilder = true)
public record TranscodeDecision(
    TranscodeMode transcodeMode,
    String videoCodecFamily,
    @NonNull Optional<EncodeCodecProfile> encodeCodecProfile,
    AudioDecision audioDecision,
    SubtitleDecision subtitleDecision,
    @NonNull Optional<VideoPlaybackFacts> videoSource) {

  public TranscodeDecision {
    var copiesVideo = transcodeMode != null && transcodeMode.copiesVideo();
    if (copiesVideo && videoSource.flatMap(VideoPlaybackFacts::codecString).isEmpty()) {
      throw new IllegalArgumentException(
          "A decision that copies the video needs the source's codec string");
    }

    if (copiesVideo && encodeCodecProfile.isPresent()) {
      throw new IllegalArgumentException(
          "A decision that copies the video cannot hold an encode codec profile");
    }

    if (encodeCodecProfile
        .filter(profile -> !profile.codecFamilyName().equals(videoCodecFamily))
        .isPresent()) {
      throw new IllegalArgumentException(
          "An encode codec profile must belong to the decision's video codec family");
    }
  }

  /**
   * This decision with other audio, such as a lower audio step (ADR 0050). Audio that no longer
   * passes through moves the mode to the one that encodes the audio, and the reverse.
   */
  public TranscodeDecision withAudioDecision(@NonNull AudioDecision audio) {
    return toBuilder()
        .audioDecision(audio)
        .transcodeMode(TranscodeMode.of(transcodeMode.copiesVideo(), audio.mode()))
        .build();
  }

  /** The output frame rate of the video source that the decision read, empty without one. */
  public Optional<Fraction> outputFrameRate() {
    return videoSource.flatMap(VideoPlaybackFacts::outputFrameRate);
  }

  // Lombok builder defaults — their generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class TranscodeDecisionBuilder {
    private Optional<EncodeCodecProfile> encodeCodecProfile = Optional.empty();
    private Optional<VideoPlaybackFacts> videoSource = Optional.empty();
  }
}
