package com.streamarr.server.domain.streaming;

import java.util.List;
import lombok.Builder;
import lombok.NonNull;

/**
 * The client capability declaration that a stream session request carries (ADR 0049): the video and
 * audio capability entries of the player and display that the stream session uses. An output fits
 * only when one capability entry accepts every property of the output.
 */
@Builder
public record ClientCapabilityDeclaration(
    @NonNull List<VideoCapabilityEntry> video, @NonNull List<AudioCapabilityEntry> audio) {

  public ClientCapabilityDeclaration {
    video = List.copyOf(video);
    audio = List.copyOf(audio);
  }

  /** Whether one video capability entry accepts the stream copy over HLS. */
  public boolean acceptsHlsCopy(@NonNull VideoStreamCopy copy) {
    return video.stream().anyMatch(entry -> entry.acceptsHlsCopy(copy));
  }
}
