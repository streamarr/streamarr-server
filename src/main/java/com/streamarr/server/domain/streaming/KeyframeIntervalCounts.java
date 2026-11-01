package com.streamarr.server.domain.streaming;

import java.util.Arrays;
import lombok.NonNull;

/**
 * One count of a stream for each keyframe interval of the chosen video stream, such as its bytes or
 * its packets, in interval order. Each count is an unsigned 32-bit value.
 */
public final class KeyframeIntervalCounts {

  private final int[] unsignedCounts;

  private KeyframeIntervalCounts(int[] unsignedCounts) {
    this.unsignedCounts = unsignedCounts;
  }

  /** The counts as unsigned 32-bit values, in interval order. The array is copied. */
  public static KeyframeIntervalCounts ofUnsigned(int @NonNull [] unsignedCounts) {
    return new KeyframeIntervalCounts(unsignedCounts.clone());
  }

  public static KeyframeIntervalCounts of(long @NonNull ... counts) {
    var unsigned = new int[counts.length];
    for (var i = 0; i < counts.length; i++) {
      if (counts[i] < 0 || counts[i] > 0xFFFF_FFFFL) {
        throw new IllegalArgumentException("A keyframe interval's count must fit 32 unsigned bits");
      }

      unsigned[i] = (int) counts[i];
    }

    return new KeyframeIntervalCounts(unsigned);
  }

  public int intervalCount() {
    return unsignedCounts.length;
  }

  public long countOf(int interval) {
    return Integer.toUnsignedLong(unsignedCounts[interval]);
  }

  /** The sum of the counts of every interval. */
  public long total() {
    var total = 0L;
    for (var count : unsignedCounts) {
      total += Integer.toUnsignedLong(count);
    }

    return total;
  }

  /** The counts as unsigned 32-bit values, in interval order. The array is a copy. */
  public int[] toUnsignedArray() {
    return unsignedCounts.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof KeyframeIntervalCounts that
        && Arrays.equals(unsignedCounts, that.unsignedCounts);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(unsignedCounts);
  }

  @Override
  public String toString() {
    return "KeyframeIntervalCounts[intervals=" + unsignedCounts.length + "]";
  }
}
