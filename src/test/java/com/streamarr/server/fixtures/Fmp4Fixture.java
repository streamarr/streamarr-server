package com.streamarr.server.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.SegmentStore;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class Fmp4Fixture {

  // ISO/IEC 14496-15 E.3: general_profile_space 0 to 3 prefixes the profile with no letter or A to
  // C.
  private static final List<String> PROFILE_SPACE_PREFIXES = List.of("", "A", "B", "C");
  // A visual sample entry's fixed fields precede its child boxes (ISO/IEC 14496-12 12.1.3).
  private static final int VISUAL_SAMPLE_ENTRY_FIELDS = 78;
  private static final int HVCC_ARRAY_COUNT_OFFSET = 22;
  private static final int PREFIX_SEI = 39;
  private static final int AUDIO_HANDLER_TYPE = 0x736F756E; // soun

  private Fmp4Fixture() {}

  /** The number of samples in the first audio track across every fragment of the media. */
  public static long audioSampleCount(byte[] media) {
    var topLevel = boxes(ByteBuffer.wrap(media));
    var track =
        requiredBox(topLevel, "moov").children("trak").stream()
            .filter(
                box -> box.child("mdia").child("hdlr").payload().getInt(8) == AUDIO_HANDLER_TYPE)
            .findFirst()
            .orElseThrow();
    var trackId = headerField(track.child("tkhd"));
    return topLevel.stream()
        .filter(box -> box.type().equals("moof"))
        .flatMap(box -> box.children("traf").stream())
        .filter(box -> Integer.toUnsignedLong(box.child("tfhd").payload().getInt(4)) == trackId)
        .flatMap(box -> box.children("trun").stream())
        .mapToLong(box -> Integer.toUnsignedLong(box.payload().getInt(4)))
        .sum();
  }

  /** The type of the first ISOBMFF box: {@code ftyp} opens an initialization segment. */
  public static String firstBoxType(byte[] media) {
    assertThat(media).hasSizeGreaterThanOrEqualTo(8);
    return new String(media, 4, 4, StandardCharsets.US_ASCII);
  }

  /** The type of the video track's sample entry, such as {@code avc1} or {@code hvc1}. */
  public static String videoSampleEntryType(byte[] initialization) {
    return videoSampleEntry(initialization).type();
  }

  /** The AVC codec parameter carried by the video track's initialization sample entry. */
  public static String avcCodecString(byte[] initialization) {
    var track =
        requiredBox(boxes(ByteBuffer.wrap(initialization)), "moov").children("trak").stream()
            .filter(Fmp4Fixture::isVideo)
            .findFirst()
            .orElseThrow();
    var descriptions = track.child("mdia").child("minf").child("stbl").child("stsd").payload();
    // stsd has a full-box header and entry count; an AVC visual sample entry has 78 fixed bytes.
    var sample =
        requiredBox(boxes(descriptions.slice(8, descriptions.remaining() - 8)), "avc1").payload();
    var configuration =
        requiredBox(boxes(sample.slice(78, sample.remaining() - 78)), "avcC").payload();
    assertThat(configuration.get(0)).isEqualTo((byte) 1);
    return "avc1.%02X%02X%02X"
        .formatted(configuration.get(1), configuration.get(2), configuration.get(3));
  }

  /**
   * The HEVC codec parameter of the video track's sample entry, built from its type and its hvcC as
   * ISO/IEC 14496-15 annex E specifies: the profile, the compatibility flags in reverse bit order,
   * the tier and level, and the constraint bytes without trailing zero bytes.
   */
  public static String hevcCodecString(byte[] initialization) {
    var entry = videoSampleEntry(initialization);
    var hvcC = entry.visualChild("hvcC").payload();
    var profileByte = Byte.toUnsignedInt(hvcC.get(1));
    var codec =
        new StringBuilder(entry.type())
            .append('.')
            .append(PROFILE_SPACE_PREFIXES.get(profileByte >> 6))
            .append(profileByte & 0x1F)
            .append('.')
            .append(Integer.toHexString(Integer.reverse(hvcC.getInt(2))).toUpperCase(Locale.ROOT))
            .append('.')
            .append((profileByte & 0x20) == 0 ? 'L' : 'H')
            .append(Byte.toUnsignedInt(hvcC.get(12)));
    var constraints = new byte[6];
    hvcC.get(6, constraints);
    var last = constraints.length;
    while (last > 0 && constraints[last - 1] == 0) {
      last--;
    }

    for (var index = 0; index < last; index++) {
      codec.append('.').append("%02X".formatted(constraints[index]));
    }

    return codec.toString();
  }

  /**
   * The messages of each prefix SEI NAL unit in the video sample entry's hvcC, in order, each with
   * its payload as an SEI message carries it (ITU-T H.265 7.3.5). The mastering display and content
   * light level messages are prefix SEI messages (D.3.28, D.3.35). Each unit's header must name the
   * base layer and a temporal identifier, as a decoder requires (7.4.2.2).
   */
  public static List<List<SeiMessage>> hvccSeiMessages(byte[] initialization) {
    return hvccNalUnits(initialization).stream()
        .filter(unit -> nalUnitType(unit) == PREFIX_SEI)
        .map(Fmp4Fixture::seiMessagesOf)
        .toList();
  }

  /**
   * The Dolby Vision configuration records (dvcC, dvvC or dvwC) of the video sample entry, each
   * checked against the box type that its profile takes.
   */
  public static List<DolbyVisionConfigurationBox> dolbyVisionRecords(byte[] initialization) {
    return boxes(videoSampleEntry(initialization).visualChildren()).stream()
        .filter(box -> box.type().matches("dv[cvw]C"))
        .map(box -> DolbyVisionConfigurationBox.read(box.type(), box.payload()))
        .toList();
  }

  /**
   * The NAL unit types of each sample, in order, for media whose fragments each hold one track run
   * of the HEVC track that the initialization segment describes, with the run's samples back to
   * back in the mdat that follows its moof. The trun's sample sizes, or the tfhd's default size,
   * delimit the samples (ISO/IEC 14496-12 8.8.7 and 8.8.8).
   */
  public static List<List<Integer>> sampleNalUnitTypes(byte[] initialization, byte[] media) {
    var hvcC = videoSampleEntry(initialization).visualChild("hvcC").payload();
    var lengthSize = (hvcC.get(21) & 0x03) + 1;
    var samples = new ArrayList<List<Integer>>();
    var run = new TrackRun(-1, List.of());
    var boxStart = 0L;
    for (var box : boxes(ByteBuffer.wrap(media))) {
      if (box.type().equals("moof")) {
        run = trackRun(box, boxStart);
      }

      if (box.type().equals("mdat")) {
        var data = box.payload();
        assertThat(run.dataStart())
            .as("the data offset of the run that precedes the mdat")
            .isEqualTo(boxStart + 8);
        assertThat(run.sampleSizes().stream().mapToInt(Integer::intValue).sum())
            .as("the sample sizes of the run that precedes the mdat")
            .isEqualTo(data.remaining());
        for (var size : run.sampleSizes()) {
          samples.add(nalUnitTypesOf(data.slice(data.position(), size), lengthSize));
          data.position(data.position() + size);
        }
      }

      boxStart += 8 + box.payload().remaining();
    }

    return samples;
  }

  // Where a track run's first sample starts in the media, and the size of each of its samples.
  private record TrackRun(long dataStart, List<Integer> sampleSizes) {}

  // The run's data offset counts from the tfhd's base data offset, or else from the moof's first
  // byte, as default-base-is-moof and the first track fragment of a moof both give.
  private static TrackRun trackRun(Box fragment, long fragmentStart) {
    var trackFragments = fragment.children("traf");
    assertThat(trackFragments).as("the track fragments of a moof").hasSize(1);
    var header = trackFragments.getFirst().child("tfhd").payload();
    var headerFlags = header.getInt(0) & 0x00FF_FFFF;
    // track_ID, then the optional base data offset, sample description index and default duration.
    var defaultSizeOffset =
        8 + ((headerFlags & 0x01) == 0 ? 0 : 8) + Integer.bitCount(headerFlags & 0x0A) * 4;
    var run = trackFragments.getFirst().child("trun").payload();
    var runFlags = run.getInt(0) & 0x00FF_FFFF;
    var count = run.getInt(4);
    assertThat(runFlags & 0x01).as("the run's data offset").isNotZero();
    var base = (headerFlags & 0x01) == 0 ? fragmentStart : header.getLong(8);
    var dataStart = base + run.getInt(8);
    // The data offset and first-sample flags precede the per-sample fields.
    var position = 8 + Integer.bitCount(runFlags & 0x005) * 4;
    var fieldsPerSample = Integer.bitCount(runFlags & 0xF00);
    var sizes = new ArrayList<Integer>();
    for (var sample = 0; sample < count; sample++) {
      var fields = position + sample * fieldsPerSample * 4;
      if ((runFlags & 0x200) == 0) {
        assertThat(headerFlags & 0x10).as("a default sample size").isNotZero();
        sizes.add(header.getInt(defaultSizeOffset));
        continue;
      }

      sizes.add(run.getInt(fields + ((runFlags & 0x100) == 0 ? 0 : 4)));
    }

    return new TrackRun(dataStart, sizes);
  }

  private static List<Integer> nalUnitTypesOf(ByteBuffer sample, int lengthSize) {
    var types = new ArrayList<Integer>();
    while (sample.hasRemaining()) {
      var length = 0;
      for (var index = 0; index < lengthSize; index++) {
        length = (length << 8) | Byte.toUnsignedInt(sample.get());
      }

      assertThat(length).as("NAL unit length").isBetween(1, sample.remaining());
      types.add(nalUnitType(Byte.toUnsignedInt(sample.get(sample.position()))));
      sample.position(sample.position() + length);
    }

    return types;
  }

  /** One SEI message: its payload type and its payload in hexadecimal, without emulation bytes. */
  public record SeiMessage(int payloadType, String payloadHex) {}

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

  // The first sample entry of the video track's stsd, whose full-box header and entry count take 8
  // bytes.
  private static Box videoSampleEntry(byte[] initialization) {
    var track =
        requiredBox(boxes(ByteBuffer.wrap(initialization)), "moov").children("trak").stream()
            .filter(Fmp4Fixture::isVideo)
            .findFirst()
            .orElseThrow();
    var descriptions = track.child("mdia").child("minf").child("stbl").child("stsd").payload();
    return boxes(descriptions.slice(8, descriptions.remaining() - 8)).getFirst();
  }

  // The NAL units of the hvcC arrays (ISO/IEC 14496-15 8.3.3.1.2), each without its length.
  private static List<byte[]> hvccNalUnits(byte[] initialization) {
    var hvcC = videoSampleEntry(initialization).visualChild("hvcC").payload();
    hvcC.position(HVCC_ARRAY_COUNT_OFFSET);
    var arrays = Byte.toUnsignedInt(hvcC.get());
    var units = new ArrayList<byte[]>();
    for (var array = 0; array < arrays; array++) {
      hvcC.get();
      var count = Short.toUnsignedInt(hvcC.getShort());
      for (var unit = 0; unit < count; unit++) {
        var bytes = new byte[Short.toUnsignedInt(hvcC.getShort())];
        hvcC.get(bytes);
        units.add(bytes);
      }
    }

    return units;
  }

  private static int nalUnitType(byte[] unit) {
    return nalUnitType(Byte.toUnsignedInt(unit[0]));
  }

  private static int nalUnitType(int firstHeaderByte) {
    return (firstHeaderByte >> 1) & 0x3F;
  }

  // The SEI messages of the NAL unit's RBSP, after its 2-byte header, with each emulation
  // prevention byte removed (ITU-T H.265 7.3.5 and 7.4.2).
  private static List<SeiMessage> seiMessagesOf(byte[] unit) {
    assertThat(unit[0] & 0x80).as("forbidden_zero_bit").isZero();
    assertThat(((unit[0] & 0x01) << 5) | (Byte.toUnsignedInt(unit[1]) >> 3))
        .as("nuh_layer_id")
        .isZero();
    assertThat(unit[1] & 0x07).as("nuh_temporal_id_plus1").isPositive();
    var rbsp = new ByteArrayOutputStream();
    var zeros = 0;
    for (var index = 2; index < unit.length; index++) {
      var octet = Byte.toUnsignedInt(unit[index]);
      if (zeros >= 2 && octet == 3) {
        zeros = 0;
        continue;
      }

      zeros = octet == 0 ? zeros + 1 : 0;
      rbsp.write(octet);
    }

    var reading = ByteBuffer.wrap(rbsp.toByteArray());
    var messages = new ArrayList<SeiMessage>();
    while (reading.remaining() > 1) {
      var type = seiCodedValue(reading);
      var payload = new byte[seiCodedValue(reading)];
      reading.get(payload);
      messages.add(new SeiMessage(type, HexFormat.of().withUpperCase().formatHex(payload)));
    }

    assertThat(Byte.toUnsignedInt(reading.get())).as("the RBSP stop byte").isEqualTo(0x80);
    return messages;
  }

  // An SEI payload type or size: each 0xFF byte adds 255 until another byte ends the value.
  private static int seiCodedValue(ByteBuffer reading) {
    var value = 0;
    var octet = 0xFF;
    while (octet == 0xFF) {
      octet = Byte.toUnsignedInt(reading.get());
      value += octet;
    }

    return value;
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

    private ByteBuffer visualChildren() {
      return payload.slice(
          VISUAL_SAMPLE_ENTRY_FIELDS, payload.remaining() - VISUAL_SAMPLE_ENTRY_FIELDS);
    }

    private Box visualChild(String childType) {
      return requiredBox(boxes(visualChildren()), childType);
    }
  }

  /**
   * The stream session's stored initialization segment followed by the media segment, which a
   * player decodes together.
   */
  public static byte[] withInitializationSegment(
      SegmentStore segmentStore, UUID sessionId, byte[] mediaSegment) {
    var media = new ByteArrayOutputStream();
    media.writeBytes(
        StoredSegments.bytes(segmentStore, sessionId, SegmentNames.INITIALIZATION_SEGMENT_NAME));
    media.writeBytes(mediaSegment);
    return media.toByteArray();
  }

  /** The stream session's stored initialization segment followed by its stored media segment. */
  public static byte[] withInitializationSegment(
      SegmentStore segmentStore, UUID sessionId, String mediaSegmentName) {
    return withInitializationSegment(
        segmentStore, sessionId, StoredSegments.bytes(segmentStore, sessionId, mediaSegmentName));
  }
}
