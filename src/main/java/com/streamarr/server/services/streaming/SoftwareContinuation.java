package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.EncodeFormat;
import lombok.NonNull;

/**
 * The encode that a format attempt continues on the software encoder after its hardware encoder
 * failed (ADR 0044): the codec family and the format of the failed encode.
 */
public record SoftwareContinuation(@NonNull String codecFamily, @NonNull EncodeFormat format) {

  /**
   * Whether the continuation may encode this format: the failed encode's own format, or 8-bit SDR
   * after a 10-bit SDR encode, which keeps its range (ADR 0013).
   */
  public boolean mayEncode(EncodeFormat candidate) {
    return candidate.equals(format)
        || (format.equals(EncodeFormat.SDR_10_BIT) && candidate.equals(EncodeFormat.SDR_8_BIT));
  }
}
