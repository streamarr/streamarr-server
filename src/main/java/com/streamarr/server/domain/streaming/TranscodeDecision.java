package com.streamarr.server.domain.streaming;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;

/**
 * {@code encodeCodecProfile} holds the codec profile that the decision's encode forces (ADR 0013),
 * so a decision that copies the video holds none. {@code videoSource} holds the playback facts of
 * the chosen video stream that the decision read. A decision that copies the video needs the codec
 * string that the probe stored, because the copy job carries it. {@code videoBitstreamRewrites}
 * lists every bitstream rewrite of the decision's copy, and a decision that encodes the video lists
 * none (ADR 0036). {@code videoDolbyVisionOutput} is the Dolby Vision of a copy that the playlist
 * describes as Dolby Vision, and a decision that encodes the video holds none (ADR 0013). {@code
 * reasons} names why the output differs from the source, video reasons before audio reasons. {@code
 * videoCapabilityEntryRefusals} lists, for each video capability entry for HLS of the source's
 * codec family, what that entry refused of the best copy that the decision could plan (#396).
 * {@code audioStreamIndex} is the index of the source audio stream that the session plays, and is
 * empty when the decision carries no audio.
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
    @NonNull Optional<DolbyVisionOutput> videoDolbyVisionOutput,
    @NonNull List<DecisionReason> reasons,
    @NonNull List<VideoCapabilityEntryRefusal> videoCapabilityEntryRefusals,
    @NonNull OptionalInt audioStreamIndex) {

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
    reasons = List.copyOf(reasons);
    videoCapabilityEntryRefusals = List.copyOf(videoCapabilityEntryRefusals);

    if (encodeCodecProfile
        .filter(profile -> !profile.codecFamilyName().equals(videoCodecFamily))
        .isPresent()) {
      throw new IllegalArgumentException(
          "An encode codec profile must belong to the decision's video codec family");
    }
  }

  /**
   * This decision with a lower audio step that the viewer's maximum bitrate takes (ADR 0050), which
   * the reasons name. Audio that no longer passes through moves the mode to the one that encodes
   * the audio, and the reverse. The decision's own audio leaves it unchanged.
   */
  public TranscodeDecision withAudioDecision(@NonNull AudioDecision audio) {
    if (audio.equals(audioDecision)) {
      return this;
    }

    return toBuilder()
        .audioDecision(audio)
        .transcodeMode(TranscodeMode.of(transcodeMode.copiesVideo(), audio.mode()))
        .reasons(withReason(reasons, DecisionReason.AUDIO_LOWERED_FOR_BITRATE_LIMIT))
        .build();
  }

  private static List<DecisionReason> withReason(
      List<DecisionReason> reasons, DecisionReason added) {
    if (reasons.contains(added)) {
      return reasons;
    }

    return Stream.concat(reasons.stream(), Stream.of(added)).toList();
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
   * The codec string of the sample entry that a copy of the video carries, which the copy's
   * playlist entry and job name: the Dolby Vision entry's for a copy of profile 5 or 10.0 (ADR
   * 0013), otherwise the one that the probe stored.
   */
  public Optional<String> copyCodecString() {
    return videoDolbyVisionOutput
        .flatMap(DolbyVisionOutput::codecs)
        .or(() -> videoSource.flatMap(VideoPlaybackFacts::codecString));
  }

  /**
   * The dynamic range of the planned video output, as a playlist names it. Dolby Vision without a
   * compatible base is PQ.
   */
  public VideoRange videoRange() {
    return VideoRange.ofCompatibleBase(plannedVideoOutput().dynamicRange().compatibleBase());
  }

  // Lombok builder defaults — their generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class TranscodeDecisionBuilder {
    private Optional<EncodeCodecProfile> encodeCodecProfile = Optional.empty();
    private Optional<VideoPlaybackFacts> videoSource = Optional.empty();
    private List<BitstreamRewrite> videoBitstreamRewrites = List.of();
    private Optional<DolbyVisionOutput> videoDolbyVisionOutput = Optional.empty();
    private List<DecisionReason> reasons = List.of();
    private List<VideoCapabilityEntryRefusal> videoCapabilityEntryRefusals = List.of();
    private OptionalInt audioStreamIndex = OptionalInt.empty();
  }
}
