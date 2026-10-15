package com.streamarr.server.domain.streaming;

import lombok.Builder;

@Builder
public record QualityVariant(
    int width, int height, long videoBitrate, long audioBitrate, String label) {

  /** The variant's AVERAGE-BANDWIDTH in a multivariant playlist whose audio has this bitrate. */
  public long averageBandwidth(long playlistAudioBitrate) {
    return videoBitrate + playlistAudioBitrate;
  }
}
