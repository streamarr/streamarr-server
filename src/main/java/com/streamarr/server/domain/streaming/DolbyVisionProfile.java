package com.streamarr.server.domain.streaming;

/**
 * A single-layer Dolby Vision profile that a video capability entry can name (ADR 0049). No value
 * names profile 7, because no supported delivery path plays two layers.
 */
public enum DolbyVisionProfile {
  PROFILE_5,
  PROFILE_8_1,
  PROFILE_8_4,
  PROFILE_10_0,
  PROFILE_10_1,
  PROFILE_10_4;

  /** The codec family whose streams carry this profile: HEVC for 5 and 8.x, AV1 for 10.x. */
  public ProbedCodecFamily codecFamily() {
    return switch (this) {
      case PROFILE_5, PROFILE_8_1, PROFILE_8_4 -> ProbedCodecFamily.HEVC;
      case PROFILE_10_0, PROFILE_10_1, PROFILE_10_4 -> ProbedCodecFamily.AV1;
    };
  }

  /** The compatible base of this profile's streams (ADR 0036): none for 5 and 10.0. */
  public CompatibleBase compatibleBase() {
    return switch (this) {
      case PROFILE_5, PROFILE_10_0 -> CompatibleBase.NONE;
      case PROFILE_8_1, PROFILE_10_1 -> CompatibleBase.PQ;
      case PROFILE_8_4, PROFILE_10_4 -> CompatibleBase.HLG;
    };
  }
}
