package com.streamarr.server.domain.streaming;

import java.util.List;
import lombok.Builder;
import lombok.NonNull;

/**
 * One audio capability entry (ADR 0049): an audio codec and the most channels that the player
 * accepts for it. {@code hls} tells whether the entry applies to HLS delivery, and {@code
 * directPlayContainers} lists the source containers from which the player opens such a stream by
 * direct play.
 */
@Builder
public record AudioCapabilityEntry(
    @NonNull AudioCodec codec,
    int maxChannels,
    boolean hls,
    @NonNull List<SourceContainer> directPlayContainers) {

  public AudioCapabilityEntry {
    directPlayContainers = List.copyOf(directPlayContainers);
  }

  // Lombok builder default — its generated setter assigns it
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class AudioCapabilityEntryBuilder {
    private List<SourceContainer> directPlayContainers = List.of();
  }
}
