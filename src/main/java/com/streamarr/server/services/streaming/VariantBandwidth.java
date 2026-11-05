package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.properties.StreamingProperties;
import lombok.NonNull;

/** A variant's BANDWIDTH and AVERAGE-BANDWIDTH in the multivariant playlist (RFC 8216 §4.3.4.2). */
record VariantBandwidth(long peak, long average) {

  // RFC 8216 §4.3.4.2 defines BANDWIDTH as the peak segment bit rate and AVERAGE-BANDWIDTH as
  // the average. An encoded variant and an unmeasured copy know only an average, so their
  // BANDWIDTH adds headroom for container overhead and rate variation (ADR 0044). Encoded
  // variants are capped with -maxrate. Probed source rates remain estimates.
  private static final int PEAK_HEADROOM_PERCENT = 20;

  static VariantBandwidth withHeadroom(long average) {
    return new VariantBandwidth(average + average * PEAK_HEADROOM_PERCENT / 100, average);
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
