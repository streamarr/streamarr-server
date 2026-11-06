package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.streaming.StreamSession;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import lombok.NonNull;

/** A variant's BANDWIDTH and AVERAGE-BANDWIDTH in the multivariant playlist (RFC 8216 §4.3.4.2). */
record VariantBandwidth(long peak, long average) {

  // RFC 8216 §4.3.4.2 defines BANDWIDTH as the peak segment bit rate and AVERAGE-BANDWIDTH as
  // the average. An encoded variant and an unmeasured copy know only an average, so their
  // BANDWIDTH adds headroom for container overhead and rate variation (ADR 0044). Encoded
  // variants are capped with -maxrate. Probed source rates remain estimates.
  private static final int PEAK_HEADROOM_PERCENT = 20;

  private static final long MILLIS_PER_SECOND = 1_000;

  // A variant that the stream session does not offer delivers nothing.
  private static final VariantBandwidth NONE = new VariantBandwidth(0, 0);

  static VariantBandwidth withHeadroom(long average) {
    return new VariantBandwidth(average + average * PEAK_HEADROOM_PERCENT / 100, average);
  }

  /**
   * The bandwidth of a stream session's variant: the source file for a direct-play stream session,
   * the copy for the default variant of a stream session without a ladder, and otherwise the
   * variant of its ladder.
   */
  static VariantBandwidth of(
      @NonNull StreamSession session,
      @NonNull String variantLabel,
      @NonNull StreamingProperties properties) {
    if (session.getDelivery() == StreamDelivery.DIRECT_PLAY) {
      return withHeadroom(directPlayAverage(session.getMediaProbe()));
    }

    var audio = session.getTranscodeDecision().audioDecision();
    if (StreamSession.defaultVariant().equals(variantLabel) && session.getVariants().isEmpty()) {
      return ofCopy(session.getMediaProbe(), audio, properties);
    }

    return session.getVariants().stream()
        .filter(variant -> variant.label().equals(variantLabel))
        .findFirst()
        .map(variant -> ofEncoded(variant, audio))
        .orElse(NONE);
  }

  /**
   * Direct play sends the whole source file. Once the bitrate scan has run, it counts at the
   * average that the scan measured over every stream (ADR 0054). Before that, and when the scan did
   * not measure every video stream, it counts at the probed container bitrate.
   */
  private static long directPlayAverage(MediaProbe probe) {
    var durationMillis = probe.duration().toMillis();
    var measurements = probe.streamMeasurements();
    if (measurements.isEmpty()
        || durationMillis <= 0
        || !measuresEveryVideoStream(probe, measurements.get())) {
      return probe.bitrate();
    }

    var measuredBits =
        measurements.get().streams().stream()
            .map(stream -> BigInteger.valueOf(stream.measuredBytes()))
            .reduce(BigInteger.ZERO, BigInteger::add)
            .shiftLeft(3);
    return new BigDecimal(measuredBits.multiply(BigInteger.valueOf(MILLIS_PER_SECOND)))
        .divide(BigDecimal.valueOf(durationMillis), 0, RoundingMode.CEILING)
        .min(BigDecimal.valueOf(Long.MAX_VALUE))
        .longValueExact();
  }

  private static boolean measuresEveryVideoStream(
      MediaProbe probe, StreamMeasurements measurements) {
    return probe.streams().stream()
        .filter(stream -> "video".equals(stream.codecType()))
        .allMatch(stream -> measurements.stream(stream.index()).isPresent());
  }

  /** An encoded variant beside the audio that every variant of the playlist carries. */
  static VariantBandwidth ofEncoded(@NonNull QualityVariant variant, @NonNull AudioDecision audio) {
    return withHeadroom(variant.averageBandwidth(audio.bitrate()));
  }

  /**
   * A measured copy advertises its measured peak. An unmeasured copy, like an encoded variant, adds
   * headroom to its average (ADR 0054).
   */
  static VariantBandwidth ofCopy(
      @NonNull MediaProbe probe,
      @NonNull AudioDecision audio,
      @NonNull StreamingProperties properties) {
    var playlist = CopyMediaPlaylist.of(probe, properties);
    var average = VideoCopyBandwidth.averageBandwidth(probe, audio, playlist);
    var peak = VideoCopyBandwidth.peakBandwidth(probe, audio, playlist);
    if (peak.isEmpty()) {
      return withHeadroom(average);
    }

    return new VariantBandwidth(peak.getAsLong(), average);
  }
}
