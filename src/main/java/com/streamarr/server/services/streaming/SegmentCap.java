package com.streamarr.server.services.streaming;

/**
 * The segment cap, a constant of the worker contract that the server and the worker each fix in
 * code: no delivered segment holds more bytes (ADR 0037).
 */
public final class SegmentCap {

  public static final int BYTES = 128 * 1024 * 1024;

  private SegmentCap() {}
}
