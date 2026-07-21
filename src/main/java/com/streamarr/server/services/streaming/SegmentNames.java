package com.streamarr.server.services.streaming;

import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Naming scheme for HLS artifacts: media segments are {@code segment{index}.{ts|m4s}}, optionally
 * under a variant directory, and each fMP4 run rewrites a sibling {@code init.mp4}.
 */
public final class SegmentNames {

  // Nine digits keep every parsed index inside int range; a longer run of digits is not a
  // media-segment name.
  private static final Pattern MEDIA_SEGMENT_PATTERN =
      Pattern.compile("segment(\\d{1,9})\\.(ts|m4s)");

  private SegmentNames() {}

  /** The segment's timeline index, or 0 when the name carries none (e.g. {@code init.mp4}). */
  public static int parseIndex(String segmentName) {
    return indexOf(segmentName).orElse(0);
  }

  /** Whether the name is a run's init segment: a basename of exactly {@code init.mp4}. */
  public static boolean isInitSegment(String segmentName) {
    return "init.mp4".equals(basename(segmentName));
  }

  public static OptionalInt indexOf(String segmentName) {
    var matcher = MEDIA_SEGMENT_PATTERN.matcher(basename(segmentName));
    if (!matcher.matches()) {
      return OptionalInt.empty();
    }

    return OptionalInt.of(Integer.parseInt(matcher.group(1)));
  }

  /**
   * The same run's media segment at {@code index}: same variant directory, same container. A name
   * matching no scheme throws rather than passing through unchanged — a fabricated sibling would
   * point progress checks at a file no run can ever produce.
   */
  public static String siblingName(String segmentName, int index) {
    var base = basename(segmentName);
    var directory = segmentName.substring(0, segmentName.length() - base.length());
    var matcher = MEDIA_SEGMENT_PATTERN.matcher(base);
    if (matcher.matches()) {
      return directory + "segment" + index + "." + matcher.group(2);
    }

    // Only fMP4 runs have an init.mp4; their media segments are .m4s.
    if (isInitSegment(segmentName)) {
      return directory + "segment" + index + ".m4s";
    }

    throw new IllegalArgumentException("Segment name matches no known scheme: " + segmentName);
  }

  private static String basename(String segmentName) {
    var slashIdx = segmentName.lastIndexOf('/');
    if (slashIdx < 0) {
      return segmentName;
    }

    return segmentName.substring(slashIdx + 1);
  }
}
