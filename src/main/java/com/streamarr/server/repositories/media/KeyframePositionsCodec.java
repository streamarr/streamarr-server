package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.exceptions.MalformedKeyframePositionsException;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * The stored form of keyframe positions. The first byte names the format. Format 1 then holds two
 * zigzag varints for each keyframe, in decode-time order: the decode time minus the previous
 * keyframe's decode time (zero before the first keyframe), and the presentation time minus the
 * keyframe's own decode time. Both differences wrap around on overflow, so every pair of long
 * values round-trips.
 */
@UtilityClass
public class KeyframePositionsCodec {

  private static final byte DECODE_DELTA_AND_PRESENTATION_OFFSET_FORMAT = 1;
  private static final int MAXIMUM_VARINT_BYTES = 10;

  public static byte[] encode(List<KeyframePosition> keyframes) {
    var encoded = new ByteArrayOutputStream();
    encoded.write(DECODE_DELTA_AND_PRESENTATION_OFFSET_FORMAT);
    var previousDecodeTime = 0L;
    for (var keyframe : keyframes) {
      writeZigzagVarint(encoded, keyframe.decodeTime() - previousDecodeTime);
      writeZigzagVarint(encoded, keyframe.presentationTime() - keyframe.decodeTime());
      previousDecodeTime = keyframe.decodeTime();
    }

    return encoded.toByteArray();
  }

  public static List<KeyframePosition> decode(byte[] encoded) {
    if (encoded.length == 0 || encoded[0] != DECODE_DELTA_AND_PRESENTATION_OFFSET_FORMAT) {
      throw new MalformedKeyframePositionsException("Unknown keyframe positions format");
    }

    var reader = new VarintReader(encoded);
    var keyframes = new ArrayList<KeyframePosition>();
    var decodeTime = 0L;
    while (reader.hasMore()) {
      decodeTime += reader.readZigzagVarint();
      if (!reader.hasMore()) {
        throw new MalformedKeyframePositionsException("Keyframe positions end inside a keyframe");
      }

      keyframes.add(new KeyframePosition(decodeTime + reader.readZigzagVarint(), decodeTime));
    }

    return keyframes;
  }

  private static void writeZigzagVarint(ByteArrayOutputStream encoded, long value) {
    var remaining = (value << 1) ^ (value >> 63);
    while ((remaining & ~0x7FL) != 0) {
      encoded.write((int) ((remaining & 0x7F) | 0x80));
      remaining >>>= 7;
    }

    encoded.write((int) remaining);
  }

  private static final class VarintReader {

    private final byte[] encoded;
    private int position = 1;

    private VarintReader(byte[] encoded) {
      this.encoded = encoded;
    }

    private boolean hasMore() {
      return position < encoded.length;
    }

    private long readZigzagVarint() {
      var value = 0L;
      for (var index = 0; index < MAXIMUM_VARINT_BYTES; index++) {
        if (!hasMore()) {
          throw new MalformedKeyframePositionsException("Keyframe positions end inside a number");
        }

        var current = encoded[position++];
        if (index == MAXIMUM_VARINT_BYTES - 1 && (current & 0x7E) != 0) {
          throw new MalformedKeyframePositionsException(
              "Keyframe positions hold a number past 64 bits");
        }

        value |= (long) (current & 0x7F) << (7 * index);
        if ((current & 0x80) == 0) {
          return (value >>> 1) ^ -(value & 1);
        }
      }

      throw new MalformedKeyframePositionsException(
          "Keyframe positions hold a number past 64 bits");
    }
  }
}
