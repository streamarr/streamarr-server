package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.exceptions.MalformedStreamMeasurementException;
import java.nio.ByteBuffer;
import lombok.experimental.UtilityClass;

/**
 * The stored form of one count of a stream for each keyframe interval. The first byte names the
 * format. Format 1 then holds one unsigned 32-bit big-endian count for each interval, in interval
 * order.
 */
@UtilityClass
public class KeyframeIntervalCountsCodec {

  private static final byte UNSIGNED_32_BIT_FORMAT = 1;

  public static byte[] encode(KeyframeIntervalCounts intervals) {
    var encoded = ByteBuffer.allocate(1 + Integer.BYTES * intervals.intervalCount());
    encoded.put(UNSIGNED_32_BIT_FORMAT);
    for (var count : intervals.toUnsignedArray()) {
      encoded.putInt(count);
    }

    return encoded.array();
  }

  public static KeyframeIntervalCounts decode(byte[] encoded) {
    if (encoded.length == 0 || encoded[0] != UNSIGNED_32_BIT_FORMAT) {
      throw new MalformedStreamMeasurementException("Unknown keyframe interval counts format");
    }

    if ((encoded.length - 1) % Integer.BYTES != 0) {
      throw new MalformedStreamMeasurementException("Keyframe interval counts end inside a count");
    }

    var buffer = ByteBuffer.wrap(encoded, 1, encoded.length - 1);
    var counts = new int[(encoded.length - 1) / Integer.BYTES];
    for (var i = 0; i < counts.length; i++) {
      counts[i] = buffer.getInt();
    }

    return KeyframeIntervalCounts.ofUnsigned(counts);
  }
}
