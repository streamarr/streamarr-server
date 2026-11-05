package com.streamarr.server.domain.streaming;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** An audio encode's output channel layout (ADR 0050), and FFmpeg's name for it. */
@Getter
@RequiredArgsConstructor
public enum ChannelLayout {
  MONO(1, "mono"),
  STEREO(2, "stereo"),
  TWO_POINT_ONE(3, "2.1"),
  THREE_POINT_ZERO(3, "3.0"),
  FOUR_POINT_ZERO(4, "4.0"),
  QUAD(4, "quad"),
  QUAD_SIDE(4, "quad(side)"),
  FIVE_POINT_ZERO(5, "5.0"),
  FIVE_POINT_ZERO_SIDE(5, "5.0(side)"),
  FIVE_POINT_ONE(6, "5.1"),
  FIVE_POINT_ONE_SIDE(6, "5.1(side)");

  private final int channels;
  private final String ffmpegName;
}
