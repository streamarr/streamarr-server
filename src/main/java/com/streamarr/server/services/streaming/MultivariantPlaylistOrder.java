package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.QualityVariant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.NonNull;

/**
 * The order in which the multivariant playlist lists a ladder (ADR 0008): first the variant whose
 * average bandwidth is nearest 2000 kb/s, then the other variants from highest to lowest.
 */
public final class MultivariantPlaylistOrder {

  // Apple's HLS Authoring Specification, item 1.32.
  private static final long FIRST_LISTED_AVERAGE_BANDWIDTH = 2_000_000L;

  private MultivariantPlaylistOrder() {}

  /**
   * The ladder in listing order. {@code playlistAudioBitrate} is the audio bitrate that every
   * variant's AVERAGE-BANDWIDTH includes.
   */
  public static List<QualityVariant> of(
      @NonNull List<QualityVariant> ladder, long playlistAudioBitrate) {
    var highestFirst = new ArrayList<>(ladder);
    highestFirst.sort(
        Comparator.comparingLong(
                (QualityVariant variant) -> variant.averageBandwidth(playlistAudioBitrate))
            .reversed());
    if (highestFirst.isEmpty()) {
      return List.of();
    }

    var nearest =
        highestFirst.stream()
            .min(
                Comparator.comparingLong(
                    variant ->
                        Math.abs(
                            variant.averageBandwidth(playlistAudioBitrate)
                                - FIRST_LISTED_AVERAGE_BANDWIDTH)))
            .orElseThrow();
    highestFirst.remove(nearest);
    highestFirst.addFirst(nearest);
    return List.copyOf(highestFirst);
  }
}
