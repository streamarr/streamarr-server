package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MeasuredStream;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.services.streaming.PeakSegmentBitRate.SegmentSize;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import lombok.NonNull;

/**
 * What a video copy costs beside the stream session's audio: the AVERAGE-BANDWIDTH that the
 * multivariant playlist advertises for the copy and the cost that the viewer's maximum bitrate
 * bounds (ADR 0050, ADR 0054), the BANDWIDTH that a measured copy advertises, and whether a
 * measured copy's media segments fit the segment cap (ADR 0037).
 */
final class VideoCopyBandwidth {

  private static final List<Integer> AAC_SAMPLE_RATES =
      List.of(
          7_350, 8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000, 64_000,
          88_200, 96_000);
  private static final List<Integer> AC3_SAMPLE_RATES = List.of(32_000, 44_100, 48_000);

  /** The FFmpeg audio encoders that a session runs, by the codec that they encode. */
  private static final Map<String, AudioEncoder> AUDIO_ENCODERS =
      Map.of(
          "aac", new AudioEncoder(1_024, 1_024, AAC_SAMPLE_RATES),
          "ac3", new AudioEncoder(1_536, 256, AC3_SAMPLE_RATES),
          "eac3", new AudioEncoder(1_536, 256, AC3_SAMPLE_RATES));

  private VideoCopyBandwidth() {}

  /**
   * The copied video's bitrate plus the stream session's audio bitrate, counted so that it never
   * falls below what the player downloads. When the server holds a measurement of the source's
   * streams and a stream copy lists the media playlist, the copy costs every byte that its media
   * segments deliver over the sum of the playlist's EXTINF durations: the measured video bytes, a
   * copied audio track's measured bytes, and the fragment framing of each segment, track and
   * sample. Encoded audio adds its bitrate and the framing of the frames that its encoder writes.
   *
   * <p>Without a measurement, the probed bitrate of the copied video counts. When the probe knows
   * no bitrate for the copied video, as for Matroska, the container bitrate stands in for it. That
   * rate already holds every source track, so a copied audio track adds nothing to it, while
   * encoded audio adds its bitrate on top. A copied audio track whose bitrate the probe does not
   * know takes a nominal bitrate, which can be far below a lossless track's rate, so a known
   * container bitrate, which holds that track, bounds the copy from below.
   */
  static long averageBandwidth(
      @NonNull MediaProbe source,
      @NonNull AudioDecision audio,
      @NonNull CopyMediaPlaylist playlist) {
    return measuredCost(source, audio, playlist).orElseGet(() -> unmeasuredCost(source, audio));
  }

  /**
   * A measured copy's peak segment bit rate: the copied video's peak plus the copied audio's peak
   * over the media segments that a stream copy lists (RFC 8216 §4.1, ADR 0054), never below the
   * copy's average. Empty when the copy has no measured average, or when the measured keyframe
   * intervals do not follow the source's keyframe positions.
   */
  static OptionalLong peakBandwidth(
      @NonNull MediaProbe source,
      @NonNull AudioDecision audio,
      @NonNull CopyMediaPlaylist playlist) {
    var average = measuredCost(source, audio, playlist);
    var positions = keyframePositions(source);
    if (average.isEmpty() || positions.isEmpty()) {
      return OptionalLong.empty();
    }

    var video = measuredVideo(source).orElseThrow();
    if (!followsKeyframes(video, positions.get())) {
      return OptionalLong.empty();
    }

    var videoPeak =
        PeakSegmentBitRate.of(
            CopySegmentSizes.video(video, playlist), playlist.targetDurationSeconds());
    var audioPeak = audioPeak(source, audio, positions.get(), playlist);
    if (audioPeak.isEmpty()) {
      return OptionalLong.empty();
    }

    // A peak over runs that miss the densest segment can fall below the average, which RFC 8216
    // never lets BANDWIDTH do.
    return OptionalLong.of(Math.max(saturatedSum(videoPeak, audioPeak.get()), average.get()));
  }

  /**
   * Whether a measured segment of the copy, with the stream session's audio for the same interval,
   * exceeds the segment cap (ADR 0037). Copied audio without a measurement counts nothing, so the
   * measured video alone bounds the segment. False when the server did not measure the copied video
   * over the source's keyframe positions: the worker's own cap check then fails the attempt.
   */
  static boolean exceedsSegmentCap(
      @NonNull MediaProbe source,
      @NonNull AudioDecision audio,
      @NonNull CopyMediaPlaylist playlist) {
    var positions = keyframePositions(source);
    var video = measuredVideo(source);
    if (positions.isEmpty()
        || video.isEmpty()
        || !playlist.isKnown()
        || !followsKeyframes(video.get(), positions.get())) {
      return false;
    }

    return SegmentCap.isExceededBy(
        CopySegmentSizes.video(video.get(), playlist),
        audioSegmentSizes(source, audio, positions.get(), playlist));
  }

  private static List<SegmentSize> audioSegmentSizes(
      MediaProbe source,
      AudioDecision audio,
      KeyframePositions positions,
      CopyMediaPlaylist playlist) {
    return switch (audio.mode()) {
      case NONE -> List.of();
      case COPY ->
          copiedAudio(source)
              .filter(copied -> followsKeyframes(copied, positions))
              .map(copied -> CopySegmentSizes.copiedAudio(copied, positions, playlist))
              .orElseGet(List::of);
      case TRANSCODE ->
          encodedAudioFrames(source, audio)
              .map(encoded -> CopySegmentSizes.encodedAudio(encoded, audio.bitrate(), playlist))
              .orElseGet(List::of);
    };
  }

  /** The largest peak among the session's audio renditions: today its one audio decision. */
  private static Optional<Long> audioPeak(
      MediaProbe source,
      AudioDecision audio,
      KeyframePositions positions,
      CopyMediaPlaylist playlist) {
    return switch (audio.mode()) {
      case NONE -> Optional.of(0L);
      case COPY ->
          copiedAudio(source)
              .filter(copied -> followsKeyframes(copied, positions))
              .map(
                  copied ->
                      PeakSegmentBitRate.of(
                          CopySegmentSizes.copiedAudio(copied, positions, playlist),
                          playlist.targetDurationSeconds()));
      case TRANSCODE ->
          encodedAudioFrames(source, audio)
              .map(
                  encoded ->
                      PeakSegmentBitRate.of(
                          CopySegmentSizes.encodedAudio(encoded, audio.bitrate(), playlist),
                          playlist.targetDurationSeconds()));
    };
  }

  private static Optional<KeyframePositions> keyframePositions(MediaProbe source) {
    return source.keyframes().usablePositions();
  }

  private static boolean followsKeyframes(MeasuredStream stream, KeyframePositions positions) {
    return stream.keyframeIntervalBytes().intervalCount() == positions.keyframes().size();
  }

  private static long unmeasuredCost(MediaProbe source, AudioDecision audio) {
    var copiesAudio = audio.mode() == AudioMode.COPY;
    var videoBitrate = copiedStreamBitrate(source);
    if (videoBitrate.isEmpty() && copiesAudio) {
      return Math.max(source.bitrate(), audio.bitrate());
    }

    var averageBandwidth = videoBitrate.orElse(source.bitrate()) + audio.bitrate();
    if (copiesAudio && source.audioBitrate().isEmpty()) {
      return Math.max(averageBandwidth, source.bitrate());
    }

    return averageBandwidth;
  }

  /**
   * Whether the server measured the copied video over a media playlist that a stream copy lists, or
   * the probe knows its bitrate or the container bitrate.
   */
  static boolean isKnown(@NonNull MediaProbe source, @NonNull CopyMediaPlaylist playlist) {
    return (playlist.isKnown() && measuredVideo(source).isPresent())
        || copiedStreamBitrate(source).isPresent()
        || source.bitrate() > 0;
  }

  /**
   * The bytes that the copy's media segments deliver over the playlist's duration, plus encoded
   * audio's bitrate. The encoder writes its frames over the playlist's duration with its priming
   * samples. Empty when the server did not measure the copied video or a copied audio track, when
   * the encoded audio's framing is not known, or when no stream copy lists the media playlist. The
   * copy carries the source's first audio stream.
   */
  private static Optional<Long> measuredCost(
      MediaProbe source, AudioDecision audio, CopyMediaPlaylist playlist) {
    var video = measuredVideo(source);
    if (video.isEmpty() || !playlist.isKnown()) {
      return Optional.empty();
    }

    var segments = BigInteger.valueOf(playlist.segmentCount());
    var videoBytes = SegmentFraming.segmentBytes(segments).add(trackBytes(video.get(), segments));
    return switch (audio.mode()) {
      case NONE -> Optional.of(averageBitRate(videoBytes, playlist));
      case COPY ->
          copiedAudio(source)
              .map(
                  copied -> averageBitRate(videoBytes.add(trackBytes(copied, segments)), playlist));
      case TRANSCODE ->
          encodedAudioFrames(source, audio)
              .map(encoded -> encoded.over(playlist.durationSeconds(), true))
              .map(
                  frames ->
                      averageBitRate(
                          videoBytes.add(SegmentFraming.trackBytes(segments, frames)), playlist))
              .map(bitRate -> saturatedSum(bitRate, audio.bitrate()));
    };
  }

  private static Optional<MeasuredStream> measuredVideo(MediaProbe source) {
    return copiedStreamIndex(source)
        .flatMap(index -> measuredStream(source, index))
        .filter(stream -> stream.measuredBytes() > 0);
  }

  private static Optional<MeasuredStream> copiedAudio(MediaProbe source) {
    return source.audioStreams().stream()
        .findFirst()
        .flatMap(stream -> measuredStream(source, stream.index()));
  }

  private static Optional<MeasuredStream> measuredStream(MediaProbe source, int streamIndex) {
    return source.streamMeasurements().flatMap(measurement -> measurement.stream(streamIndex));
  }

  /** A track's measured bytes and its framing in every segment. */
  private static BigInteger trackBytes(MeasuredStream stream, BigInteger segments) {
    return BigInteger.valueOf(stream.measuredBytes())
        .add(
            SegmentFraming.trackBytes(
                segments, new BigInteger(Long.toUnsignedString(stream.packetCount()))));
  }

  /**
   * The frames that the session's audio encoder writes, at the lowest rate that it takes at or
   * above the source's first audio stream's rate, or at its highest rate when that rate is higher
   * or not known. Empty for an encoder whose framing is not known.
   */
  private static Optional<EncodedAudioFrames> encodedAudioFrames(
      MediaProbe source, AudioDecision audio) {
    var encoder = Optional.ofNullable(audio.codec()).map(AUDIO_ENCODERS::get);
    if (encoder.isEmpty()) {
      return Optional.empty();
    }

    var rate = encoder.get().highestRate();
    var sourceRate =
        source.audioStreams().stream()
            .findFirst()
            .map(StreamInfo::sampleRate)
            .orElse(OptionalInt.empty());
    if (sourceRate.isPresent()) {
      rate = encoder.get().rateAtOrAbove(sourceRate.getAsInt());
    }

    return Optional.of(
        new EncodedAudioFrames(encoder.get().frameSamples(), encoder.get().primingSamples(), rate));
  }

  /** The bytes in bits per second of the playlist's EXTINF durations, rounded up. */
  private static long averageBitRate(BigInteger bytes, CopyMediaPlaylist playlist) {
    var bitRate =
        new BigDecimal(bytes.shiftLeft(3))
            .divide(playlist.durationSeconds(), 0, RoundingMode.CEILING)
            .toBigIntegerExact();
    return bitRate.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
  }

  private static long saturatedSum(long first, long second) {
    return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
  }

  private static Optional<Integer> copiedStreamIndex(MediaProbe source) {
    return source.videoPlaybackFacts().map(VideoPlaybackFacts::streamIndex);
  }

  private static OptionalLong copiedStreamBitrate(MediaProbe source) {
    var streamIndex = copiedStreamIndex(source);
    if (streamIndex.isEmpty()) {
      return OptionalLong.empty();
    }

    return source.streams().stream()
        .filter(stream -> stream.index() == streamIndex.get())
        .flatMapToLong(stream -> stream.bitrate().stream())
        .findFirst();
  }

  /**
   * An audio encoder that writes frames of {@code frameSamples} after {@code primingSamples} of
   * priming, at one of {@code sampleRates} in ascending order.
   */
  private record AudioEncoder(long frameSamples, long primingSamples, List<Integer> sampleRates) {

    int highestRate() {
      return sampleRates.getLast();
    }

    /** The lowest rate at or above the source's, or the highest rate below it. */
    int rateAtOrAbove(int sourceRate) {
      return sampleRates.stream()
          .filter(rate -> rate >= sourceRate)
          .findFirst()
          .orElseGet(this::highestRate);
    }
  }
}
