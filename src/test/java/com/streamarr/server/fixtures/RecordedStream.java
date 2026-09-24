package com.streamarr.server.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import lombok.RequiredArgsConstructor;

/**
 * Fragmented MP4 that the pinned worker image's FFmpeg wrote to standard output with the worker's
 * command recipe (ADR 0037), for a 6 s segment period. {@code
 * src/test/resources/recorded-fmp4/record.sh} regenerates every recording.
 */
@RequiredArgsConstructor
public enum RecordedStream {
  /** Media time 0 to 8 s: an initialization segment, then media segments 0 and 1. */
  START_AT_ZERO("start-0s.fmp4"),
  /** A replacement attempt from 6 s: the same initialization segment, then media segment 1. */
  SEEK_TO_SIX_SECONDS("seek-6s.fmp4"),
  /** The same seek from a different encoder backend, whose initialization segment differs. */
  SEEK_TO_SIX_SECONDS_WITH_DIFFERING_INITIALIZATION("seek-6s-differing-initialization.fmp4");

  private final String fileName;

  public byte[] bytes() {
    try (var input = RecordedStream.class.getResourceAsStream("/recorded-fmp4/" + fileName)) {
      assertThat(input).as("recorded stream %s", fileName).isNotNull();
      return input.readAllBytes();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /** Where the worker container fixture places the recording for a scripted FFmpeg to read. */
  public String containerPath() {
    return "/tmp/recorded-fmp4/" + fileName;
  }
}
