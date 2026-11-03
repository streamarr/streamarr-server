package com.streamarr.server.domain.streaming;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** An audio encode's output channel layout (ADR 0050). Each comment gives FFmpeg's name. */
@Getter
@RequiredArgsConstructor
public enum ChannelLayout {
  // mono
  MONO(1),
  // stereo
  STEREO(2),
  // 5.0
  FIVE_POINT_ZERO(5),
  // 5.0(side)
  FIVE_POINT_ZERO_SIDE(5),
  // 5.1
  FIVE_POINT_ONE(6),
  // 5.1(side)
  FIVE_POINT_ONE_SIDE(6);

  private final int channels;
}
