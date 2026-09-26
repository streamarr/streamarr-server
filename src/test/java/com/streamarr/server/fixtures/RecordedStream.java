package com.streamarr.server.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import lombok.RequiredArgsConstructor;

/**
 * Fragmented MP4 that the pinned worker image's FFmpeg wrote to standard output with the command
 * the worker builds for a libx264 encode of the committed 10 s clip (ADR 0037): a 6 s target
 * segment duration, and keyframes forced at the advertised boundaries from the job attempt's start
 * sequence number. Each recording ends at media time 8 s. {@code
 * src/test/resources/recorded-fmp4/record.sh} regenerates every recording and holds each command.
 */
@RequiredArgsConstructor
public enum RecordedStream {
  /**
   * The initial job attempt, media time 0 to 8 s: an initialization segment, then media segments 0
   * and 1. An encoded replacement attempt for segment 1 writes these same bytes, which {@code
   * record.sh} checks.
   */
  START_AT_ZERO("start-0s.fmp4"),
  /**
   * That replacement attempt from a different encoder backend: its initialization segment differs,
   * and its segment 0 is again preroll.
   */
  REPLACEMENT_WITH_DIFFERING_INITIALIZATION("replacement-differing-initialization.fmp4");

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
