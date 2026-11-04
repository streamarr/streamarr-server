package com.streamarr.server.services.streaming;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Stream copy media playlists whose segment {@code N} opens at the keyframe at index {@code N}, so
 * that each segment holds one keyframe interval, on grid slots as long as the target duration.
 */
@UtilityClass
class CopyMediaPlaylistFixture {

  /** Segments that last these EXTINF durations, under this EXT-X-TARGETDURATION. */
  static CopyMediaPlaylist listing(int targetDurationSeconds, String... extinfSeconds) {
    return listing(targetDurationSeconds, List.of(extinfSeconds));
  }

  /** This many segments of the same EXTINF duration, which is also the target duration. */
  static CopyMediaPlaylist evenlyListing(int segmentCount, String extinfSeconds) {
    return listing(
        new BigDecimal(extinfSeconds).intValue(), Collections.nCopies(segmentCount, extinfSeconds));
  }

  private static CopyMediaPlaylist listing(int targetDurationSeconds, List<String> extinfSeconds) {
    var segments = new ArrayList<CopyMediaPlaylist.Segment>();
    for (var index = 0; index < extinfSeconds.size(); index++) {
      segments.add(
          CopyMediaPlaylist.Segment.builder()
              .openingKeyframeIndex(index)
              .slotStartSeconds(index * targetDurationSeconds)
              .extinfSeconds(new BigDecimal(extinfSeconds.get(index)))
              .build());
    }

    return new CopyMediaPlaylist(segments, targetDurationSeconds);
  }
}
