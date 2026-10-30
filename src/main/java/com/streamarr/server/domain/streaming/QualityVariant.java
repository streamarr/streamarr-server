package com.streamarr.server.domain.streaming;

import lombok.Builder;
import lombok.NonNull;

/**
 * One encoded variant of a stream session's ladder. {@code frameRate} is the exact rate at which
 * the variant's encode runs, which its job attempts, its codec level and its playlist entry share.
 */
@Builder
public record QualityVariant(
    int width, int height, long videoBitrate, String label, @NonNull Fraction frameRate) {

  /** The variant's AVERAGE-BANDWIDTH in a multivariant playlist whose audio has this bitrate. */
  public long averageBandwidth(long playlistAudioBitrate) {
    return videoBitrate + playlistAudioBitrate;
  }
}
