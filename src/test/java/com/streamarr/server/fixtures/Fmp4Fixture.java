package com.streamarr.server.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.SegmentStore;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Fmp4Fixture {

  private Fmp4Fixture() {}

  /** The type of the first ISOBMFF box: {@code ftyp} opens an initialization segment. */
  public static String firstBoxType(byte[] media) {
    assertThat(media).hasSizeGreaterThanOrEqualTo(8);
    return new String(media, 4, 4, StandardCharsets.US_ASCII);
  }

  /** The first video sample's composition time in seconds, without a demuxer's timestamp shift. */
  public static double firstVideoPresentationTime(byte[] media) {
    var topLevel = boxes(ByteBuffer.wrap(media));
    var track =
        requiredBox(topLevel, "moov").children("trak").stream()
            .filter(Fmp4Fixture::isVideo)
            .findFirst()
            .orElseThrow();
    var trackId = headerField(track.child("tkhd"));
    var timescale = headerField(track.child("mdia").child("mdhd"));
    assertThat(timescale).isPositive();
    var fragment =
        topLevel.stream()
            .filter(box -> box.type().equals("moof"))
            .flatMap(box -> box.children("traf").stream())
            .filter(box -> Integer.toUnsignedLong(box.child("tfhd").payload().getInt(4)) == trackId)
            .findFirst()
            .orElseThrow();
    return (decodeTime(fragment.child("tfdt")) + compositionOffset(fragment.child("trun")))
        / (double) timescale;
  }

  private static boolean isVideo(Box track) {
    var handler = track.child("mdia").child("hdlr").payload();
    var type = new byte[4];
    handler.get(8, type);
    return new String(type, StandardCharsets.US_ASCII).equals("vide");
  }

  private static long headerField(Box header) {
    var fields = header.payload();
    var timestampsLength = fields.get(0) == 1 ? 16 : 8;
    return Integer.toUnsignedLong(fields.getInt(4 + timestampsLength));
  }

  private static long decodeTime(Box decodeTime) {
    var fields = decodeTime.payload();
    return fields.get(0) == 1 ? fields.getLong(4) : Integer.toUnsignedLong(fields.getInt(4));
  }

  private static long compositionOffset(Box run) {
    var fields = run.payload();
    var flags = fields.getInt(0) & 0x00FF_FFFF;
    assertThat(Integer.toUnsignedLong(fields.getInt(4))).as("video sample count").isPositive();
    if ((flags & 0x800) == 0) {
      return 0;
    }

    // data offset, first-sample flags, duration, size and flags precede the composition offset.
    var offset = 8 + 4 * Integer.bitCount(flags & 0x705);
    return fields.get(0) == 1
        ? fields.getInt(offset)
        : Integer.toUnsignedLong(fields.getInt(offset));
  }

  private static List<Box> boxes(ByteBuffer contents) {
    var fields = contents.asReadOnlyBuffer();
    var result = new ArrayList<Box>();
    while (fields.hasRemaining()) {
      assertThat(fields.remaining()).as("box header").isGreaterThanOrEqualTo(8);
      var size = fields.getInt();
      assertThat(size).as("box size").isBetween(8, fields.remaining() + 4);
      var type = new byte[4];
      fields.get(type);
      result.add(
          new Box(
              new String(type, StandardCharsets.US_ASCII),
              fields.slice(fields.position(), size - 8)));
      fields.position(fields.position() + size - 8);
    }

    return result;
  }

  private static Box requiredBox(List<Box> boxes, String type) {
    return boxes.stream().filter(box -> box.type().equals(type)).findFirst().orElseThrow();
  }

  private record Box(String type, ByteBuffer payload) {

    private List<Box> children(String childType) {
      return boxes(payload).stream().filter(box -> box.type().equals(childType)).toList();
    }

    private Box child(String childType) {
      return children(childType).getFirst();
    }
  }

  /**
   * The stream session's stored initialization segment followed by the media segment, which a
   * player decodes together.
   */
  public static byte[] withInitializationSegment(
      SegmentStore segmentStore, UUID sessionId, byte[] mediaSegment) {
    var media = new ByteArrayOutputStream();
    media.writeBytes(segmentStore.readSegment(sessionId, SegmentNames.INITIALIZATION_SEGMENT_NAME));
    media.writeBytes(mediaSegment);
    return media.toByteArray();
  }

  /** The stream session's stored initialization segment followed by its stored media segment. */
  public static byte[] withInitializationSegment(
      SegmentStore segmentStore, UUID sessionId, String mediaSegmentName) {
    return withInitializationSegment(
        segmentStore, sessionId, segmentStore.readSegment(sessionId, mediaSegmentName));
  }
}
