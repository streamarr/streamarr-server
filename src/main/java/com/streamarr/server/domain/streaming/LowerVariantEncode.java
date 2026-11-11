package com.streamarr.server.domain.streaming;

import lombok.NonNull;

/**
 * The encode that every lower variant beside a copy runs in Auto (ADR 0050): the codec profile that
 * it forces, and the range and bit depth that it writes. The codec profile holds that bit depth, so
 * H.265 Main 10 never pairs with 8 bits (#58).
 */
public record LowerVariantEncode(
    @NonNull EncodeCodecProfile codecProfile, @NonNull EncodeFormat format) {

  public LowerVariantEncode {
    if (!codecProfile.holdsBitDepth(format.bitDepth())) {
      throw new IllegalArgumentException(
          "A lower variant's codec profile must hold the bit depth of its encode format");
    }
  }
}
