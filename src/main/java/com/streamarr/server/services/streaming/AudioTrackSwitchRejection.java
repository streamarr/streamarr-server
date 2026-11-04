package com.streamarr.server.services.streaming;

import lombok.NonNull;

/** Why a playback session refused the viewer's switch to another audio track. */
public sealed interface AudioTrackSwitchRejection {

  record PlaybackSessionClosed() implements AudioTrackSwitchRejection {}

  record AudioTrackNotInFile() implements AudioTrackSwitchRejection {}

  /** The playback session has no format attempt left, so direct play goes on. */
  record FormatAttemptsExhausted() implements AudioTrackSwitchRejection {}

  /** The stream session for the chosen track did not start, so direct play goes on. */
  record StreamSessionRefused(@NonNull CreateStreamSessionRejection rejection)
      implements AudioTrackSwitchRejection {}
}
