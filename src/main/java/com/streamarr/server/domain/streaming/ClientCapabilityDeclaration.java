package com.streamarr.server.domain.streaming;

import java.util.List;
import java.util.Optional;
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

  /**
   * The encoded output that one video capability entry accepts over HLS (ADR 0049), or empty when
   * no entry accepts an encode of the request. Among the entries' fitted outputs, the larger
   * picture wins, then the faster frame rate.
   */
  public Optional<EncodedVideoOutput> fitHlsEncode(@NonNull EncodedVariantRequest request) {
    return video.stream()
        .map(entry -> entry.fitHlsEncode(request))
        .flatMap(Optional::stream)
        .max(EncodedVideoOutput.LARGER_PICTURE_THEN_FASTER_FRAME_RATE);
  }
}
