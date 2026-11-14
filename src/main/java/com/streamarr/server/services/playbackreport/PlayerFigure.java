package com.streamarr.server.services.playbackreport;

/** One group of player figures, which a rejection names when its value is out of range. */
public enum PlayerFigure {
  QUALITY_STEP,
  OBSERVED_BITRATE,
  BUFFER_SECONDS,
  DROPPED_FRAMES,
  STALLS,
  RESOLUTION,
  VIDEO_CODEC,
  AUDIO,
  DISPLAY,
  AUDIO_OUTPUT,
  SCREEN,
  BROWSER
}
