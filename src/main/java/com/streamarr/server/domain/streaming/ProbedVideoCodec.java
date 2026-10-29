package com.streamarr.server.domain.streaming;

import lombok.NonNull;

/**
 * A video stream's codec, read from the codec name that the probe stores, such as {@code hevc}.
 * Every codec that the server has no rules for reads as {@link #OTHER}.
 */
public enum ProbedVideoCodec {
  H264,
  HEVC,
  AV1,
  OTHER;

  public static ProbedVideoCodec fromProbedName(@NonNull String name) {
    return switch (name) {
      case "h264" -> H264;
      case "hevc" -> HEVC;
      case "av1" -> AV1;
      default -> OTHER;
    };
  }

  /**
   * Whether the probe walks this codec's extradata and, beside its first frames, its first packets.
   * Only a walk rules a frame feature out, places an RPU or tells whether the packets carry
   * presentation times.
   */
  public boolean isWalkedByProbe() {
    return switch (this) {
      case H264, HEVC, AV1 -> true;
      case OTHER -> false;
    };
  }

  /**
   * Whether an RPU header with profile 0 and a full-range base layer marks a stream whose Dolby
   * Vision configuration record names no profile as one without a compatible base: profile 5 in
   * HEVC and profile 10.0 in AV1.
   */
  public boolean hasRecordLessBaselessProfile() {
    return switch (this) {
      case HEVC, AV1 -> true;
      case H264, OTHER -> false;
    };
  }
}
