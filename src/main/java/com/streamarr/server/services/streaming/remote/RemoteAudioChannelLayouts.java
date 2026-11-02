package com.streamarr.server.services.streaming.remote;

import com.streamarr.transcode.v1.AudioChannelLayout;

// A job that names a layout of 3, 4, 7 or 8 channels needs a worker that encodes in the named
// layout. For 1, 2, 5 and 6 channels, FFmpeg's default layout for the count with -ac is the layout
// that the server names, so an older worker encodes the same channels (ADR 0032).
final class RemoteAudioChannelLayouts {

  private RemoteAudioChannelLayouts() {}

  static boolean needsLayoutEncodingWorker(AudioChannelLayout layout) {
    return switch (layout) {
      case AUDIO_CHANNEL_LAYOUT_TWO_POINT_ONE,
          AUDIO_CHANNEL_LAYOUT_THREE_POINT_ZERO,
          AUDIO_CHANNEL_LAYOUT_THREE_POINT_ZERO_BACK,
          AUDIO_CHANNEL_LAYOUT_FOUR_POINT_ZERO,
          AUDIO_CHANNEL_LAYOUT_QUAD,
          AUDIO_CHANNEL_LAYOUT_QUAD_SIDE,
          AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE,
          AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE_BACK,
          AUDIO_CHANNEL_LAYOUT_SEVEN_POINT_ONE,
          AUDIO_CHANNEL_LAYOUT_SEVEN_POINT_ONE_WIDE ->
          true;
      case AUDIO_CHANNEL_LAYOUT_UNSPECIFIED,
          AUDIO_CHANNEL_LAYOUT_MONO,
          AUDIO_CHANNEL_LAYOUT_STEREO,
          AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO,
          AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO_SIDE,
          AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE,
          AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE_SIDE,
          UNRECOGNIZED ->
          false;
    };
  }
}
