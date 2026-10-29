package com.streamarr.server.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Places Dolby Vision RPUs in HEVC streams, so a test can build each RPU layout from FATE's samples
 * at run time. An HEVC RPU is a NAL unit of type 62.
 */
public final class DolbyVisionRpuFixture {

  private static final byte[] START_CODE = {0, 0, 0, 1};
  private static final int HEVC_RPU_NAL_UNIT_TYPE = 62;
  private static final int FIRST_NON_VCL_NAL_UNIT_TYPE = 32;
  private static final int BOX_HEADER_BYTES = 8;
  private static final int NAL_LENGTH_BYTES = 4;
  // The stsz payload holds a full box's version and flags, the sample size, the sample count and
  // then one size per sample. The stco payload holds version and flags, the chunk count and then
  // one offset per chunk.
  private static final int STSZ_SAMPLE_SIZE = 4;
  private static final int STSZ_SAMPLE_COUNT = 8;
  private static final int STSZ_ENTRIES = 12;
  private static final int STCO_CHUNK_COUNT = 4;
  private static final int STCO_FIRST_OFFSET = 8;

  private DolbyVisionRpuFixture() {}

  /** The first RPU of a raw HEVC stream, without its start code. */
  public static byte[] firstRpu(byte[] annexB) {
    return nalUnits(annexB).stream()
        .filter(unit -> nalUnitType(unit[0]) == HEVC_RPU_NAL_UNIT_TYPE)
        .findFirst()
        .orElseThrow(() -> new AssertionError("The raw HEVC stream holds no RPU"));
  }

  /**
   * A raw HEVC stream with the RPU after each picture's last slice segment, where Dolby Vision
   * places a picture's RPU. FFmpeg's HEVC parser starts no access unit at an RPU, so each sample of
   * a remux ends with its picture's RPU.
   */
  public static byte[] withRpuAfterEachPicture(byte[] annexB, byte[] rpu) {
    var units = nalUnits(annexB);
    var stream = new ByteArrayOutputStream();
    for (var index = 0; index < units.size(); index++) {
      writeNalUnit(stream, units.get(index));
      if (endsPicture(units, index)) {
        writeNalUnit(stream, rpu);
      }
    }

    return stream.toByteArray();
  }

  /**
   * Writes the shifted layout of a Dolby Vision HEVC stream in MP4, as mkvmerge wrote it before
   * version 57: each picture's RPU starts the next picture's sample. The source must hold one track
   * whose samples fill one chunk, the whole payload of an {@code mdat} box ahead of the {@code
   * moov} box, and end each sample with its picture's RPU.
   *
   * <p>The samples keep their bytes in the same order, so moving each sample boundary ahead of the
   * RPU before it moves that RPU into the next sample. The last RPU has no next picture, so the
   * layout leaves it out, as the worker's {@code move-rpus-ahead.py} does. Only the sample sizes
   * and the {@code mdat} size change. The timestamps and the edit list stay.
   */
  public static byte[] shiftEachRpuIntoTheNextSample(byte[] conformingMp4) {
    var mdat = box(conformingMp4, "mdat");
    var stco = box(conformingMp4, "moov", "trak", "mdia", "minf", "stbl", "stco");
    var stsz = box(conformingMp4, "moov", "trak", "mdia", "minf", "stbl", "stsz");
    assertThat(stsz.start()).as("the moov box follows the mdat box").isGreaterThan(mdat.end());
    assertThat(payloadInt(conformingMp4, stco, STCO_CHUNK_COUNT)).as("one chunk").isOne();
    assertThat(payloadInt(conformingMp4, stco, STCO_FIRST_OFFSET))
        .as("the chunk starts the mdat payload")
        .isEqualTo(mdat.payloadStart());
    assertThat(payloadInt(conformingMp4, stsz, STSZ_SAMPLE_SIZE))
        .as("each sample has its own size")
        .isZero();

    var sizes = new int[payloadInt(conformingMp4, stsz, STSZ_SAMPLE_COUNT)];
    for (var index = 0; index < sizes.length; index++) {
      sizes[index] = payloadInt(conformingMp4, stsz, STSZ_ENTRIES + index * Integer.BYTES);
    }

    assertThat(Arrays.stream(sizes).sum())
        .as("the samples fill the mdat payload")
        .isEqualTo(mdat.end() - mdat.payloadStart());
    var rpuBytes = trailingRpuBytes(conformingMp4, mdat.payloadStart(), sizes);
    var lastRpuBytes = rpuBytes[sizes.length - 1];

    var shifted = ByteBuffer.allocate(conformingMp4.length - lastRpuBytes);
    shifted.put(conformingMp4, 0, mdat.end() - lastRpuBytes);
    shifted.put(conformingMp4, mdat.end(), conformingMp4.length - mdat.end());
    shifted.putInt(mdat.start(), mdat.size() - lastRpuBytes);
    var sizeEntries = stsz.payloadStart() - lastRpuBytes + STSZ_ENTRIES;
    for (var index = 0; index < sizes.length; index++) {
      var leadingRpuBytes = index == 0 ? 0 : rpuBytes[index - 1];
      shifted.putInt(
          sizeEntries + index * Integer.BYTES, sizes[index] - rpuBytes[index] + leadingRpuBytes);
    }

    return shifted.array();
  }

  // A picture ends at its last slice segment: the next NAL unit is no slice segment that
  // continues the picture, whose first_slice_segment_in_pic_flag is 0.
  private static boolean endsPicture(List<byte[]> units, int index) {
    if (!isSliceSegment(units.get(index))) {
      return false;
    }

    if (index + 1 == units.size()) {
      return true;
    }

    var next = units.get(index + 1);
    return !isSliceSegment(next) || (next[2] & 0x80) != 0;
  }

  private static boolean isSliceSegment(byte[] unit) {
    return nalUnitType(unit[0]) < FIRST_NON_VCL_NAL_UNIT_TYPE;
  }

  private static int nalUnitType(byte firstHeaderByte) {
    return (firstHeaderByte >> 1) & 0x3F;
  }

  // The NAL units between start codes. The zero bytes ahead of a start code belong to no unit.
  private static List<byte[]> nalUnits(byte[] annexB) {
    var starts = new ArrayList<Integer>();
    var position = 0;
    while (position + 2 < annexB.length) {
      if (annexB[position] == 0 && annexB[position + 1] == 0 && annexB[position + 2] == 1) {
        starts.add(position + 3);
        position += 3;
        continue;
      }

      position++;
    }

    var units = new ArrayList<byte[]>();
    for (var index = 0; index < starts.size(); index++) {
      var start = starts.get(index);
      var end = index + 1 < starts.size() ? starts.get(index + 1) - 3 : annexB.length;
      while (end > start && annexB[end - 1] == 0) {
        end--;
      }

      units.add(Arrays.copyOfRange(annexB, start, end));
    }

    return units;
  }

  private static void writeNalUnit(ByteArrayOutputStream stream, byte[] unit) {
    stream.writeBytes(START_CODE);
    stream.writeBytes(unit);
  }

  // The bytes of the RPU that ends each sample, its length prefix included.
  private static int[] trailingRpuBytes(byte[] mp4, int firstSampleStart, int[] sizes) {
    var rpuBytes = new int[sizes.length];
    var sampleStart = firstSampleStart;
    for (var index = 0; index < sizes.length; index++) {
      var sampleEnd = sampleStart + sizes[index];
      var lastUnit = lastNalUnitStart(mp4, sampleStart, sampleEnd);
      assertThat(nalUnitType(mp4[lastUnit + NAL_LENGTH_BYTES]))
          .as("the NAL unit type that ends sample %d", index)
          .isEqualTo(HEVC_RPU_NAL_UNIT_TYPE);
      rpuBytes[index] = sampleEnd - lastUnit;
      sampleStart = sampleEnd;
    }

    return rpuBytes;
  }

  private static int lastNalUnitStart(byte[] mp4, int sampleStart, int sampleEnd) {
    var unit = sampleStart;
    var next = nextNalUnit(mp4, unit);
    while (next < sampleEnd) {
      unit = next;
      next = nextNalUnit(mp4, unit);
    }

    assertThat(next).as("the last NAL unit ends its sample").isEqualTo(sampleEnd);
    return unit;
  }

  private static int nextNalUnit(byte[] mp4, int unit) {
    return unit + NAL_LENGTH_BYTES + ByteBuffer.wrap(mp4, unit, NAL_LENGTH_BYTES).getInt();
  }

  private static int payloadInt(byte[] mp4, Box box, int offset) {
    return ByteBuffer.wrap(mp4, box.payloadStart() + offset, Integer.BYTES).getInt();
  }

  // The first box of each type along the path, from the top level down.
  private static Box box(byte[] mp4, String topLevelType, String... descendantTypes) {
    var found = firstBox(mp4, 0, mp4.length, topLevelType);
    for (var type : descendantTypes) {
      found = firstBox(mp4, found.payloadStart(), found.end(), type);
    }

    return found;
  }

  private static Box firstBox(byte[] mp4, int start, int end, String type) {
    var position = start;
    while (position + BOX_HEADER_BYTES <= end) {
      var size = ByteBuffer.wrap(mp4, position, Integer.BYTES).getInt();
      assertThat(size).as("a 32-bit box size").isGreaterThanOrEqualTo(BOX_HEADER_BYTES);
      if (type.equals(new String(mp4, position + 4, 4, StandardCharsets.ISO_8859_1))) {
        return new Box(position, size);
      }

      position += size;
    }

    throw new AssertionError("The MP4 has no " + type + " box where the path expects one");
  }

  private record Box(int start, int size) {
    int payloadStart() {
      return start + BOX_HEADER_BYTES;
    }

    int end() {
      return start + size;
    }
  }
}
