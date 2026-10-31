package com.streamarr.server.domain.streaming;

import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * The measurement of a file's video and audio streams (ADR 0054). Each stream's keyframe intervals
 * follow the keyframe positions that the file's probe outcome stores.
 */
@Builder
public record StreamMeasurements(
    @NonNull StreamMeasurementSource source, @NonNull List<MeasuredStream> streams) {

  /**
   * The format of the measurements that the server stores. A new value discards every stored
   * measurement, apart from the probe version.
   */
  public static final int CURRENT_VERSION = 1;

  public StreamMeasurements {
    streams = List.copyOf(streams);
  }

  public Optional<MeasuredStream> stream(int streamIndex) {
    return streams.stream().filter(stream -> stream.streamIndex() == streamIndex).findFirst();
  }
}
