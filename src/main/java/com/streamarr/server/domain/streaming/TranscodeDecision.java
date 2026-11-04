package com.streamarr.server.domain.streaming;

import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * {@code encodeCodecProfile} holds the codec profile that the decision's encode forces (ADR 0013),
 * so a decision that copies the video holds none. {@code videoSource} holds the playback facts of
 * the chosen video stream that the decision read. A decision that copies the video needs the codec
 * string that the probe stored, because the copy job carries it. {@code videoBitstreamRewrites}
 * lists every bitstream rewrite of the decision's copy, and a decision that encodes the video lists
 * none (ADR 0036). {@code videoDolbyVisionOutput} is the Dolby Vision of a copy that the playlist
 * describes as Dolby Vision, and a decision that encodes the video holds none (ADR 0013).
 */
@Builder(toBuilder = true)
public record TranscodeDecision(
    TranscodeMode transcodeMode,
    String videoCodecFamily,
    @NonNull Optional<EncodeCodecProfile> encodeCodecProfile,
    AudioDecision audioDecision,
    SubtitleDecision subtitleDecision,
    @NonNull Optional<VideoPlaybackFacts> videoSource,
    @NonNull List<BitstreamRewrite> videoBitstreamRewrites,
    @NonNull Optional<DolbyVisionOutput> videoDolbyVisionOutput) {

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

    if (!copiesVideo && !videoBitstreamRewrites.isEmpty()) {
      throw new IllegalArgumentException(
          "A decision that encodes the video cannot list a bitstream rewrite");
    }

    if (!copiesVideo && videoDolbyVisionOutput.isPresent()) {
      throw new IllegalArgumentException(
          "A decision that encodes the video cannot hold a Dolby Vision output");
    }

    videoBitstreamRewrites = List.copyOf(videoBitstreamRewrites);

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

  /**
   * How the encode deinterlaces an interlaced source; empty for a copy and for a source whose
   * stored field order is progressive or unspecified. An interlaced source is never copied.
   */
  public Optional<Deinterlace> deinterlace() {
    if (transcodeMode == null || transcodeMode.copiesVideo()) {
      return Optional.empty();
    }

    return videoSource.flatMap(VideoPlaybackFacts::deinterlace);
  }

  /**
   * The frame rate that the decision's encode runs at: the stored output frame rate of the video
   * source, or the deinterlacer's rate. Empty when the probe stored no rate.
   */
  public Optional<Fraction> outputFrameRate() {
    if (transcodeMode == null || transcodeMode.copiesVideo()) {
      return videoSource.flatMap(VideoPlaybackFacts::outputFrameRate);
    }

    return videoSource.flatMap(VideoPlaybackFacts::encodedFrameRate);
  }

  /** What the decision plans the video to become: a copy of the chosen stream or an encode. */
  public PlannedVideoOutput plannedVideoOutput() {
    if (transcodeMode == null || !transcodeMode.copiesVideo()) {
      return PlannedVideoOutput.Encode.sdr(encodeCodecProfile);
    }

    var copy = PlannedVideoOutput.StreamCopy.of(videoSource.orElseThrow(), videoBitstreamRewrites);
    return videoDolbyVisionOutput.map(copy::withDolbyVisionOutput).orElse(copy);
  }

  /**
   * The dynamic range of the planned video output, as a playlist names it. Dolby Vision without a
   * compatible base is PQ.
   */
  public VideoRange videoRange() {
    return rangeOf(plannedVideoOutput().dynamicRange().compatibleBase());
  }

  private static VideoRange rangeOf(CompatibleBase base) {
    return switch (base) {
      case SDR, UNSPECIFIED -> VideoRange.SDR;
      case PQ, NONE -> VideoRange.PQ;
      case HLG -> VideoRange.HLG;
    };
  }

  // Lombok builder defaults — their generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class TranscodeDecisionBuilder {
    private Optional<EncodeCodecProfile> encodeCodecProfile = Optional.empty();
    private Optional<VideoPlaybackFacts> videoSource = Optional.empty();
    private List<BitstreamRewrite> videoBitstreamRewrites = List.of();
    private Optional<DolbyVisionOutput> videoDolbyVisionOutput = Optional.empty();
  }
}
