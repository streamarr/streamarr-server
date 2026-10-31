package com.streamarr.server.domain.streaming;

import java.util.Arrays;
import lombok.NonNull;

/**
 * One stream's bytes in each keyframe interval of the chosen video stream, in interval order. Each
 * count is an unsigned 32-bit value.
 */
public final class KeyframeIntervalBytes {

  private final int[] unsignedCounts;

  private KeyframeIntervalBytes(int[] unsignedCounts) {
    this.unsignedCounts = unsignedCounts;
  }

  /** The counts as unsigned 32-bit values, in interval order. The array is copied. */
  public static KeyframeIntervalBytes ofUnsigned(int @NonNull [] unsignedCounts) {
    return new KeyframeIntervalBytes(unsignedCounts.clone());
  }

  public static KeyframeIntervalBytes of(long @NonNull ... counts) {
    var unsigned = new int[counts.length];
    for (var i = 0; i < counts.length; i++) {
      if (counts[i] < 0 || counts[i] > 0xFFFF_FFFFL) {
        throw new IllegalArgumentException("A keyframe interval's bytes must fit 32 unsigned bits");
      }

      unsigned[i] = (int) counts[i];
    }

    return new KeyframeIntervalBytes(unsigned);
  }

  public int intervalCount() {
    return unsignedCounts.length;
  }

  public long bytesOf(int interval) {
    return Integer.toUnsignedLong(unsignedCounts[interval]);
  }

  /** The counts as unsigned 32-bit values, in interval order. The array is a copy. */
  public int[] toUnsignedArray() {
    return unsignedCounts.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof KeyframeIntervalBytes that
        && Arrays.equals(unsignedCounts, that.unsignedCounts);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(unsignedCounts);
  }

  @Override
  public String toString() {
    return "KeyframeIntervalBytes[intervals=" + unsignedCounts.length + "]";
  }
}
