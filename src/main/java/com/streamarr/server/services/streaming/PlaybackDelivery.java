package com.streamarr.server.services.streaming;

/**
 * How a format attempt delivers a track: the source file as it is, a stream copy of the source's
 * track, or an encode.
 */
public enum PlaybackDelivery {
  DIRECT_PLAY,
  COPY,
  ENCODE
}
