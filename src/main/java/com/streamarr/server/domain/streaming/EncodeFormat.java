package com.streamarr.server.domain.streaming;

import lombok.NonNull;

/**
 * The dynamic range and bit depth that an encode writes (#397). A PQ or HLG encode is 10-bit, and
 * every encode is 4:2:0.
 */
public record EncodeFormat(@NonNull VideoRange range, int bitDepth) {

  /** 8-bit SDR, which every encoder writes and every capability entry for HLS can accept. */
  public static final EncodeFormat SDR_8_BIT = new EncodeFormat(VideoRange.SDR, 8);

  /** 10-bit SDR. */
  public static final EncodeFormat SDR_10_BIT = new EncodeFormat(VideoRange.SDR, 10);

  public EncodeFormat {
    if (bitDepth != 8 && bitDepth != 10) {
      throw new IllegalArgumentException("An encode's bit depth must be 8 or 10");
    }

    if (range != VideoRange.SDR && bitDepth != 10) {
      throw new IllegalArgumentException("A PQ or HLG encode must be 10-bit");
    }
  }

  /** The 10-bit encode of a PQ or HLG range. */
  public static EncodeFormat hdr(@NonNull VideoRange range) {
    return new EncodeFormat(range, 10);
  }

  /** The compatible base that the encode's output signals. */
  public CompatibleBase compatibleBase() {
    return switch (range) {
      case SDR -> CompatibleBase.SDR;
      case PQ -> CompatibleBase.PQ;
      case HLG -> CompatibleBase.HLG;
    };
  }

  /** Whether the encode keeps an HDR range. */
  public boolean isHdr() {
    return range != VideoRange.SDR;
  }
}
