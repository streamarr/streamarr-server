package com.streamarr.server.domain.streaming;

/** A dynamic range whose facts contradict each other under ADR 0036. */
public class ContradictoryDynamicRangeException extends RuntimeException {

  public ContradictoryDynamicRangeException(String message) {
    super(message);
  }
}
