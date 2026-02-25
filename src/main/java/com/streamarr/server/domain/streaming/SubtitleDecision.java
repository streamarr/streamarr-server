package com.streamarr.server.domain.streaming;

public record SubtitleDecision(SubtitleMode mode, String codec, int streamIndex, String language) {

  public static SubtitleDecision exclude() {
    return new SubtitleDecision(SubtitleMode.EXCLUDE, null, -1, null);
  }
}
