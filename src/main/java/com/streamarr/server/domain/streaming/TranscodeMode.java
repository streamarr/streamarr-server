package com.streamarr.server.domain.streaming;

import lombok.NonNull;

public enum TranscodeMode {
  REMUX,
  AUDIO_TRANSCODE,
  VIDEO_TRANSCODE,
  FULL_TRANSCODE;

  /**
   * The mode of a decision that copies the video or encodes it, beside audio in this mode. Copied
   * audio and no audio pass through.
   */
  public static TranscodeMode of(boolean copiesVideo, @NonNull AudioMode audio) {
    var audioPassesThrough = audio == AudioMode.COPY || audio == AudioMode.NONE;
    if (copiesVideo && audioPassesThrough) {
      return REMUX;
    }

    if (copiesVideo) {
      return AUDIO_TRANSCODE;
    }

    if (audioPassesThrough) {
      return VIDEO_TRANSCODE;
    }

    return FULL_TRANSCODE;
  }

  /** Whether the mode passes the source video through unchanged, a stream copy. */
  public boolean copiesVideo() {
    return switch (this) {
      case REMUX, AUDIO_TRANSCODE -> true;
      case VIDEO_TRANSCODE, FULL_TRANSCODE -> false;
    };
  }
}
