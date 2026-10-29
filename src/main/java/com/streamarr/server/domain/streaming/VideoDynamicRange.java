package com.streamarr.server.domain.streaming;

import lombok.NonNull;

/** The dynamic range of one video stream, as ADR 0036 defines it. */
public record VideoDynamicRange(
    @NonNull CompatibleBase compatibleBase,
    @NonNull Presence hdr10Plus,
    @NonNull DolbyVision dolbyVision) {

  /** The dynamic range of a stream whose probe reported none. */
  public static final VideoDynamicRange UNSPECIFIED =
      new VideoDynamicRange(
          CompatibleBase.UNSPECIFIED, Presence.UNSPECIFIED, new DolbyVision.Unspecified());
}
