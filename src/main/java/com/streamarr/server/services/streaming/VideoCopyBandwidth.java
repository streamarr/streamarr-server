package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import java.math.BigInteger;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.NonNull;

/**
 * What a video copy costs beside the stream session's audio: the AVERAGE-BANDWIDTH that the
 * multivariant playlist advertises for the copy and the cost that the viewer's maximum bitrate
 * bounds (ADR 0050, ADR 0054).
 */
final class VideoCopyBandwidth {

  private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

  private VideoCopyBandwidth() {}

  /**
   * The copied video's bitrate plus the stream session's audio bitrate, counted so that it never
   * falls below what the player downloads. When the server holds a measurement of the source's
   * streams, the copied video costs its average segment bit rate: its measured bytes over the media
   * playlist's duration. A copied audio track then adds its own average segment bit rate, so a
   * track that ends early adds less than its own rate, and encoded audio adds its bitrate.
   *
   * <p>Without a measurement, the probed bitrate of the copied video counts. When the probe knows
   * no bitrate for the copied video, as for Matroska, the container bitrate stands in for it. That
   * rate already holds every source track, so a copied audio track adds nothing to it, while
   * encoded audio adds its bitrate on top. A copied audio track whose bitrate the probe does not
   * know takes a nominal bitrate, which can be far below a lossless track's rate, so a known
   * container bitrate, which holds that track, bounds the copy from below.
   */
  static long averageBandwidth(@NonNull MediaProbe source, @NonNull AudioDecision audio) {
    var measuredCost = measuredCost(source, audio);
    if (measuredCost.isPresent()) {
      return measuredCost.getAsLong();
    }

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
   * Whether the server measured the copied video, or the probe knows its bitrate or the container
   * bitrate.
   */
  static boolean isKnown(@NonNull MediaProbe source) {
    return measuredVideoBitRate(source).isPresent()
        || copiedStreamBitrate(source).isPresent()
        || source.bitrate() > 0;
  }

  /**
   * The measured video's average plus the session's audio, or empty when the server measured
   * neither the copied video nor, for a copied audio track, that track. The copy carries the
   * source's first audio stream.
   */
  private static OptionalLong measuredCost(MediaProbe source, AudioDecision audio) {
    var video = measuredVideoBitRate(source);
    if (video.isEmpty()) {
      return OptionalLong.empty();
    }

    if (audio.mode() != AudioMode.COPY) {
      return OptionalLong.of(saturatedSum(video.getAsLong(), audio.bitrate()));
    }

    var copiedAudio = source.audioStreams().stream().findFirst().map(StreamInfo::index);
    var audioBitRate = averageSegmentBitRate(source, copiedAudio);
    if (audioBitRate.isEmpty()) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(saturatedSum(video.getAsLong(), audioBitRate.getAsLong()));
  }

  /** The copied video's average segment bit rate, when the server measured bytes of it. */
  private static OptionalLong measuredVideoBitRate(MediaProbe source) {
    var streamIndex = copiedStreamIndex(source);
    var hasBytes =
        streamIndex
            .flatMap(index -> source.streamMeasurements().flatMap(m -> m.stream(index)))
            .filter(stream -> stream.measuredBytes() > 0)
            .isPresent();
    return hasBytes ? averageSegmentBitRate(source, streamIndex) : OptionalLong.empty();
  }

  /**
   * The stream's measured bytes in bits per second of the media playlist, rounded up, or empty when
   * the server holds no measurement of the stream or the playlist has no duration.
   */
  private static OptionalLong averageSegmentBitRate(
      MediaProbe source, Optional<Integer> streamIndex) {
    var duration = source.duration();
    var measured =
        streamIndex.flatMap(
            index -> source.streamMeasurements().flatMap(measurement -> measurement.stream(index)));
    if (measured.isEmpty() || duration == null || !duration.isPositive()) {
      return OptionalLong.empty();
    }

    var bits = BigInteger.valueOf(measured.get().measuredBytes()).shiftLeft(3);
    var nanos =
        BigInteger.valueOf(duration.getSeconds())
            .multiply(NANOS_PER_SECOND)
            .add(BigInteger.valueOf(duration.getNano()));
    var bitRate = ceilDiv(bits.multiply(NANOS_PER_SECOND), nanos);
    return OptionalLong.of(bitRate.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact());
  }

  private static long saturatedSum(long first, long second) {
    return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
  }

  private static BigInteger ceilDiv(BigInteger dividend, BigInteger divisor) {
    var quotient = dividend.divideAndRemainder(divisor);
    return quotient[1].signum() == 0 ? quotient[0] : quotient[0].add(BigInteger.ONE);
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
}
