package com.streamarr.server.domain.streaming;

public enum TranscodeMode {
  REMUX,
  AUDIO_TRANSCODE,
  VIDEO_TRANSCODE,
  FULL_TRANSCODE;

  /** Whether the mode passes the source video through unchanged, a stream copy. */
  public boolean copiesVideo() {
    return switch (this) {
      case REMUX, AUDIO_TRANSCODE -> true;
      case VIDEO_TRANSCODE, FULL_TRANSCODE -> false;
    };
  }
}
