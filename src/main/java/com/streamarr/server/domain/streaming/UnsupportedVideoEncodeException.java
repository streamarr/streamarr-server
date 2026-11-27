package com.streamarr.server.domain.streaming;

/** The server cannot plan the codec profile, tier and level that an encode would force. */
public class UnsupportedVideoEncodeException extends RuntimeException {

  public UnsupportedVideoEncodeException(String message) {
    super(message);
  }
}
