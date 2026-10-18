package com.streamarr.server.exceptions;

public class PlaybackSessionClosedException extends RuntimeException {
  public PlaybackSessionClosedException() {
    super("The playback session is closed.");
  }
}
