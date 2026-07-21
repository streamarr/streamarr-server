package com.streamarr.server.services.streaming;

import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Naming scheme for HLS artifacts: media segments are {@code segment{index}.{ts|m4s}}, optionally
 * under a variant directory, and each fMP4 run rewrites a sibling {@code init.mp4}.
 */
public final class SegmentNames {

  private static final Pattern SEGMENT_INDEX_PATTERN = Pattern.compile("segment(\\d+)");

  private SegmentNames() {}

  /** The segment's timeline index, or 0 when the name carries none (e.g. {@code init.mp4}). */
  public static int parseIndex(String segmentName) {
    return indexOf(segmentName).orElse(0);
  }

  public static OptionalInt indexOf(String segmentName) {
    var matcher = SEGMENT_INDEX_PATTERN.matcher(basename(segmentName));
    if (!matcher.find()) {
      return OptionalInt.empty();
    }

    return OptionalInt.of(Integer.parseInt(matcher.group(1)));
  }

  /** The same run's media segment at {@code index}: same variant directory, same container. */
  public static String siblingName(String segmentName, int index) {
    if (indexOf(segmentName).isPresent()) {
      return SEGMENT_INDEX_PATTERN.matcher(segmentName).replaceFirst("segment" + index);
    }

    // Only fMP4 runs have an init.mp4; their media segments are .m4s.
    return segmentName.replace("init.mp4", "segment" + index + ".m4s");
  }

  private static String basename(String segmentName) {
    var slashIdx = segmentName.lastIndexOf('/');
    if (slashIdx < 0) {
      return segmentName;
    }

    return segmentName.substring(slashIdx + 1);
  }
}
