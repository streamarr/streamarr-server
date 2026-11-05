package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoRange;
import com.streamarr.server.domain.streaming.VideoStreamCopy;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;

/**
 * How one format attempt of a playback session delivers the media title: its video and audio source
 * and output, and the reasons that the output differs from the source (#751). {@code formatAttempt}
 * counts from 1 for the initial format. {@code audio} is empty for a source without audio.
 */
@Builder
public record PlaybackDecision(
    int formatAttempt,
    @NonNull UUID streamSessionId,
    @NonNull TrackDelivery<VideoFormat> video,
    @NonNull Optional<TrackDelivery<AudioFormat>> audio,
    @NonNull List<DecisionReason> reasons) {

  public PlaybackDecision {
    reasons = List.copyOf(reasons);
  }

  /** How one track is delivered, and its source and output formats. */
  @Builder
  public record TrackDelivery<F>(
      @NonNull PlaybackDelivery delivery, @NonNull F source, @NonNull F output) {}

  /**
   * The decision of a stream session that a format attempt started. {@code cause} is the reason
   * that an earlier format attempt's failure gives this one, which comes before the stream
   * decision's own reasons.
   */
  static PlaybackDecision of(
      int formatAttempt, @NonNull StreamSession session, @NonNull Optional<DecisionReason> cause) {
    var source = session.getMediaProbe();
    if (session.getDelivery() == StreamDelivery.DIRECT_PLAY) {
      return directPlay(formatAttempt, session, cause);
    }

    var decision = session.getTranscodeDecision();
    return PlaybackDecision.builder()
        .formatAttempt(formatAttempt)
        .streamSessionId(session.getSessionId())
        .video(video(source, decision, session.getVariants()))
        .audio(audio(source, decision.audioDecision()))
        .reasons(Stream.concat(cause.stream(), decision.reasons().stream()).toList())
        .build();
  }

  // A direct-play stream session sends the source file as it is, so each output is its source.
  private static PlaybackDecision directPlay(
      int formatAttempt, StreamSession session, Optional<DecisionReason> cause) {
    var source = session.getMediaProbe();
    var video = videoSource(source);
    var audio = audioSource(source).map(PlaybackDecision::asItIs);
    return PlaybackDecision.builder()
        .formatAttempt(formatAttempt)
        .streamSessionId(session.getSessionId())
        .video(asItIs(video))
        .audio(audio)
        .reasons(cause.stream().toList())
        .build();
  }

  private static <F> TrackDelivery<F> asItIs(F format) {
    return new TrackDelivery<>(PlaybackDelivery.DIRECT_PLAY, format, format);
  }

  private static Optional<AudioFormat> audioSource(MediaProbe source) {
    if (source.audioCodec() == null || source.audioChannels().isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(
        AudioFormat.builder()
            .codec(source.audioCodec())
            .channels(source.audioChannels().getAsInt())
            .bitrate(source.audioBitrate())
            .build());
  }

  private static TrackDelivery<VideoFormat> video(
      MediaProbe source, TranscodeDecision decision, List<QualityVariant> variants) {
    var sourceFormat = videoSource(source);
    if (decision.transcodeMode().copiesVideo()) {
      var output =
          sourceFormat.toBuilder()
              .videoRange(decision.videoRange())
              .dolbyVision(hasDolbyVision(decision.plannedVideoOutput().dynamicRange()))
              .build();
      return new TrackDelivery<>(PlaybackDelivery.COPY, sourceFormat, output);
    }

    var output =
        largest(variants)
            .map(variant -> encodedVariant(decision, variant))
            .orElseGet(() -> sourceFormat.toBuilder().codec(decision.videoCodecFamily()).build())
            .toBuilder()
            .videoRange(decision.videoRange())
            .dolbyVision(false)
            .build();
    return new TrackDelivery<>(PlaybackDelivery.ENCODE, sourceFormat, output);
  }

  private static VideoFormat videoSource(MediaProbe source) {
    var picture = source.displayedSize();
    var facts = source.videoPlaybackFacts();
    var range = facts.map(VideoPlaybackFacts::dynamicRange);
    return VideoFormat.builder()
        .codec(Optional.ofNullable(source.videoCodec()).orElse(VideoFormat.UNKNOWN_CODEC))
        .width(picture.width())
        .height(picture.height())
        .frameRate(frameRate(source))
        .bitrate(videoStreamBitrate(source, facts))
        .videoRange(range.map(it -> rangeOf(it.compatibleBase())).orElse(VideoRange.SDR))
        .dolbyVision(range.filter(PlaybackDecision::hasDolbyVision).isPresent())
        .build();
  }

  // The frame-rate rule's output rate is the rate an encode runs at, which can be below the
  // source's measured rate, so the source keeps the rate that a copy carries.
  private static OptionalDouble frameRate(MediaProbe source) {
    return VideoStreamCopy.of(source)
        .map(VideoStreamCopy::frameRate)
        .filter(OptionalDouble::isPresent)
        .orElse(source.framerate());
  }

  private static OptionalLong videoStreamBitrate(
      MediaProbe source, Optional<VideoPlaybackFacts> facts) {
    if (facts.isEmpty()) {
      return OptionalLong.empty();
    }

    var streamIndex = facts.get().streamIndex();
    return source.streams().stream()
        .filter(stream -> stream.index() == streamIndex)
        .map(StreamInfo::bitrate)
        .flatMapToLong(OptionalLong::stream)
        .findFirst();
  }

  private static Optional<QualityVariant> largest(List<QualityVariant> variants) {
    return variants.stream()
        .max(Comparator.comparingLong(variant -> (long) variant.width() * variant.height()));
  }

  private static VideoFormat encodedVariant(TranscodeDecision decision, QualityVariant variant) {
    return VideoFormat.builder()
        .codec(decision.videoCodecFamily())
        .width(variant.width())
        .height(variant.height())
        .frameRate(OptionalDouble.of(variant.frameRate().toDouble()))
        .bitrate(OptionalLong.of(variant.videoBitrate()))
        .videoRange(VideoRange.SDR)
        .dolbyVision(false)
        .build();
  }

  // Dolby Vision without a compatible base plays as PQ, as a playlist names it.
  private static VideoRange rangeOf(CompatibleBase base) {
    return switch (base) {
      case SDR, UNSPECIFIED -> VideoRange.SDR;
      case PQ, NONE -> VideoRange.PQ;
      case HLG -> VideoRange.HLG;
    };
  }

  private static boolean hasDolbyVision(VideoDynamicRange range) {
    return range.dolbyVision() instanceof DolbyVision.Present;
  }

  private static Optional<TrackDelivery<AudioFormat>> audio(
      MediaProbe source, AudioDecision decision) {
    if (decision.mode() == AudioMode.NONE || source.audioCodec() == null) {
      return Optional.empty();
    }

    var sourceFormat =
        AudioFormat.builder()
            .codec(source.audioCodec())
            .channels(source.audioChannels().orElse(decision.channels()))
            .bitrate(source.audioBitrate())
            .build();
    var output =
        AudioFormat.builder()
            .codec(decision.codec())
            .channels(decision.channels())
            .bitrate(OptionalLong.of(decision.bitrate()))
            .build();
    var delivery = PlaybackDelivery.ENCODE;
    if (decision.mode() == AudioMode.COPY) {
      delivery = PlaybackDelivery.COPY;
    }

    return Optional.of(new TrackDelivery<>(delivery, sourceFormat, output));
  }
}
