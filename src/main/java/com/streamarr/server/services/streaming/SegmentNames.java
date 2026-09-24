package com.streamarr.server.services.streaming;

import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Naming scheme for HLS artifacts, which are fMP4 only: media segments are {@code
 * segment{index}.m4s}, optionally under a variant directory, beside the variant's {@code init.mp4},
 * which the variant stores once and keeps across job attempts.
 */
public final class SegmentNames {

  /** The basename of a variant's initialization segment. */
  public static final String INITIALIZATION_SEGMENT_NAME = "init.mp4";

  /** The extension of every media segment's name. */
  public static final String MEDIA_SEGMENT_EXTENSION = ".m4s";

  // Nine digits keep every parsed index inside int range; a longer run of digits is not a
  // media-segment name.
  private static final Pattern MEDIA_SEGMENT_PATTERN =
      Pattern.compile("segment(\\d{1,9})" + Pattern.quote(MEDIA_SEGMENT_EXTENSION));

  private SegmentNames() {}

  /** The name of the media segment at {@code index} on the variant's zero-based timeline. */
  public static String mediaSegmentName(int index) {
    return "segment" + index + MEDIA_SEGMENT_EXTENSION;
  }

  /** Whether the name carries the extension of a media segment or an initialization segment. */
  public static boolean hasFmp4Extension(String segmentName) {
    return segmentName.endsWith(MEDIA_SEGMENT_EXTENSION) || segmentName.endsWith(".mp4");
  }

  /** Whether the name is an initialization segment: a basename of exactly {@code init.mp4}. */
  public static boolean isInitSegment(String segmentName) {
    return INITIALIZATION_SEGMENT_NAME.equals(basename(segmentName));
  }

  public static OptionalInt indexOf(String segmentName) {
    var matcher = MEDIA_SEGMENT_PATTERN.matcher(basename(segmentName));
    if (!matcher.matches()) {
      return OptionalInt.empty();
    }

    return OptionalInt.of(Integer.parseInt(matcher.group(1)));
  }

  /**
   * The same job attempt's media segment at {@code index}, in the same variant directory. A name
   * matching no scheme throws rather than passing through unchanged — a fabricated sibling would
   * point progress checks at a file no job attempt can ever produce.
   */
  public static String siblingName(String segmentName, int index) {
    var base = basename(segmentName);
    if (!MEDIA_SEGMENT_PATTERN.matcher(base).matches() && !isInitSegment(segmentName)) {
      throw new IllegalArgumentException("Segment name matches no known scheme: " + segmentName);
    }

    var directory = segmentName.substring(0, segmentName.length() - base.length());
    return directory + mediaSegmentName(index);
  }

  private static String basename(String segmentName) {
    var slashIdx = segmentName.lastIndexOf('/');
    if (slashIdx < 0) {
      return segmentName;
    }

    return segmentName.substring(slashIdx + 1);
  }
}
