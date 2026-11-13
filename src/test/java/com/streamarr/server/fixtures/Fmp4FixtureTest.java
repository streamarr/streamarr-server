package com.streamarr.server.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Fragmented MP4 Fixture Tests")
class Fmp4FixtureTest {

  // An IDR slice, an RPU and a trailing slice, each behind a 4-byte length.
  private static final byte[] IDR_UNIT = HexFormat.of().parseHex("000000032601AF");
  private static final byte[] RPU_UNIT = HexFormat.of().parseHex("000000027C01");
  private static final byte[] TRAILING_UNIT = HexFormat.of().parseHex("000000030201AF");

  // Main 10 (profile 2) with compatibility flag 2 and the progressive and frame-only constraints.
  private static final byte[] MAIN_10_FIELDS = HexFormat.of().parseHex("0220000000900000000000");

  @ParameterizedTest
  @CsvSource({"0, 0, 0", "141, 141, 282", "140, 141, 281"})
  @DisplayName("Should count only audio samples when a segment contains multiple track runs")
  void shouldCountOnlyAudioSamplesWhenASegmentContainsMultipleTrackRuns(
      int firstRun, int secondRun, int expected) {
    var media = new ByteArrayOutputStream();
    media.writeBytes(box("moov", track("vide", 2, 128), track("soun", 7, 48_000)));
    media.writeBytes(
        box(
            "moof",
            box("traf", box("tfhd", words(0, 2)), box("trun", words(0, 144))),
            box(
                "traf",
                box("tfhd", words(0, 7)),
                box("trun", words(0, firstRun)),
                box("trun", words(0, secondRun)))));

    assertThat(Fmp4Fixture.audioSampleCount(media.toByteArray())).isEqualTo(expected);
  }

  @Test
  @DisplayName(
      "Should read the unshifted presentation time when CMAF contains negative composition offsets")
  void shouldReadTheUnshiftedPresentationTimeWhenCmafContainsNegativeCompositionOffsets() {
    assertThat(Fmp4Fixture.firstVideoPresentationTime(RecordedStream.START_AT_ZERO.bytes()))
        .isZero();
  }

  @ParameterizedTest
  @CsvSource({"1, 6.0", "0, 33554438.0"})
  @DisplayName(
      "Should use the video track and versioned composition offset when audio precedes video")
  void shouldUseTheVideoTrackAndVersionedCompositionOffsetWhenAudioPrecedesVideo(
      int runVersion, double expectedSeconds) {
    var media = new ByteArrayOutputStream();
    media.writeBytes(box("moov", track("soun", 2, 48_000), track("vide", 7, 128)));
    media.writeBytes(
        box(
            "moof",
            box(
                "traf",
                box("tfhd", words(0, 2)),
                box("tfdt", words(0, 48_000)),
                box("trun", words(0, 1))),
            box(
                "traf",
                box("tfhd", words(0, 7)),
                box("tfdt", words(0x0100_0000), ByteBuffer.allocate(8).putLong(896).array()),
                box("trun", words(runVersion << 24 | 0x800, 1, -128)))));

    assertThat(Fmp4Fixture.firstVideoPresentationTime(media.toByteArray()))
        .isEqualTo(expectedSeconds);
  }

  @Test
  @DisplayName("Should read a media time of zero when the video track has no edit list")
  void shouldReadAMediaTimeOfZeroWhenTheVideoTrackHasNoEditList() {
    var initialization = box("moov", track("vide", 1, 128));

    assertThat(Fmp4Fixture.videoEditMediaTime(initialization)).isZero();
  }

  // ISO/IEC 14496-12 8.6.6: a player subtracts the edit's media time, in the media's timescale of
  // 128, from each composition time. Version 1 holds 64-bit times. The audio track's edit list
  // starts elsewhere, so reading it gives another time.
  @ParameterizedTest
  @CsvSource({"0, 256, 2.0", "1, 64, 0.5"})
  @DisplayName(
      "Should read the edit's media time in the video track's timescale when the video track's edit"
          + " list holds one edit at media rate one")
  void shouldReadTheEditsMediaTimeWhenTheVideoTracksEditListHoldsOneEditAtMediaRateOne(
      int version, long mediaTime, double expectedSeconds) {
    var initialization =
        box(
            "moov",
            trackWithEditList("soun", editList(version, new Edit(1_000, 1))),
            trackWithEditList("vide", editList(version, new Edit(mediaTime, 1))));

    assertThat(Fmp4Fixture.videoEditMediaTime(initialization)).isEqualTo(expectedSeconds);
  }

  // Media Source Extensions ISO BMFF byte stream format, section 3: a player must support an edit
  // list of one edit at media rate one. An empty edit has the media time -1 (ISO/IEC 14496-12
  // 8.6.6.3).
  @ParameterizedTest
  @MethodSource("editListsThatAPlayerNeedNotSupport")
  @DisplayName(
      "Should refuse the video track's edit list when it holds an edit list that a player need not"
          + " support")
  void shouldRefuseTheVideoTracksEditListWhenItHoldsAnEditListThatAPlayerNeedNotSupport(
      byte[] editList, String field) {
    var initialization = box("moov", trackWithEditList("vide", editList));

    assertThatThrownBy(() -> Fmp4Fixture.videoEditMediaTime(initialization))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(field);
  }

  static Stream<Arguments> editListsThatAPlayerNeedNotSupport() {
    return Stream.of(
        Arguments.of(
            Named.of("an empty edit", editList(0, new Edit(-1, 1))), "the media time of the"),
        Arguments.of(
            Named.of("two edits", editList(0, new Edit(0, 1), new Edit(128, 1))),
            "the edits of the"),
        Arguments.of(
            Named.of("an edit at media rate two", editList(0, new Edit(0, 2))),
            "the integer part of the edit's media rate"));
  }

  // A track of the timescale 128 whose edts holds the edit list.
  private static byte[] trackWithEditList(String handler, byte[] editList) {
    var mediaHeader = ByteBuffer.allocate(24).putInt(12, 128).array();
    var handlerHeader = ByteBuffer.allocate(25).put(8, handler.getBytes(StandardCharsets.US_ASCII));
    return box(
        "trak",
        box("tkhd", new byte[84]),
        box("edts", editList),
        box("mdia", box("mdhd", mediaHeader), box("hdlr", handlerHeader.array())));
  }

  private record Edit(long mediaTime, int mediaRate) {}

  // Each edit holds a segment duration of 0, which spans the whole presentation, its media time and
  // its media rate (ISO/IEC 14496-12 8.6.6.2).
  private static byte[] editList(int version, Edit... edits) {
    var timeBytes = 4;
    if (version == 1) {
      timeBytes = 8;
    }

    var list =
        ByteBuffer.allocate(8 + edits.length * (2 * timeBytes + 4))
            .putInt(version << 24)
            .putInt(edits.length);
    for (var edit : edits) {
      putSegmentDurationAndMediaTime(list, version, edit.mediaTime());
      list.putShort((short) edit.mediaRate()).putShort((short) 0);
    }

    return box("elst", list.array());
  }

  private static void putSegmentDurationAndMediaTime(ByteBuffer list, int version, long mediaTime) {
    if (version == 1) {
      list.putLong(0).putLong(mediaTime);
      return;
    }

    list.putInt(0).putInt((int) mediaTime);
  }

  // ISO/IEC 14496-15 E.3 gives hev1.1.6.L93.B0 for Main at level 3.1; Main 10 sets compatibility
  // flag 2 alone, and a zero byte ahead of a non-zero constraint byte stays.
  @ParameterizedTest
  @CsvSource({
    "hev1, 01, 60000000, 93, B00000000000, hev1.1.6.L93.B0",
    "hvc1, 02, 20000000, 123, B00000000000, hvc1.2.4.L123.B0",
    "hvc1, 62, 20000000, 150, 900001000000, hvc1.A2.4.H150.90.00.01"
  })
  @DisplayName(
      "Should build the HEVC codec parameter of annex E when the sample entry's hvcC names the"
          + " profile, compatibility, tier, level and constraints")
  void shouldBuildTheAnnexECodecParameterWhenTheHvccNamesTheProfileTierAndLevel(
      String sampleEntry,
      String profileByte,
      String compatibility,
      int level,
      String constraints,
      String expected) {
    var hvcC =
        hvcc(
            HexFormat.of().parseHex(profileByte + compatibility + constraints), level, new byte[0]);

    assertThat(Fmp4Fixture.hevcCodecString(hevcInitialization(sampleEntry, box("hvcC", hvcC))))
        .isEqualTo(expected);
  }

  @Test
  @DisplayName(
      "Should read each SEI message without its emulation prevention bytes when the hvcC holds an"
          + " SEI NAL unit")
  void shouldReadEachSeiMessageWithoutEmulationPreventionBytesWhenTheHvccHoldsAnSeiNalUnit() {
    // A content light level message of MaxCLL 0 and MaxFALL 1 whose payload needs an emulation
    // prevention byte, then a second message of payload type 300, coded as 255 and 45.
    var messages =
        Fmp4Fixture.hvccSeiMessages(
            initializationWithSei("4E01" + "90040000030001" + "FF2D01AB" + "80"));

    assertThat(messages)
        .containsExactly(
            List.of(
                new Fmp4Fixture.SeiMessage(144, "00000001"),
                new Fmp4Fixture.SeiMessage(300, "AB")));
  }

  // A suffix SEI unit (type 40) carries no mastering display or content light level message.
  @Test
  @DisplayName("Should read no message from a suffix SEI unit when the hvcC holds only that unit")
  void shouldReadNoMessageFromASuffixSeiUnitWhenTheHvccHoldsOnlyThatUnit() {
    assertThat(Fmp4Fixture.hvccSeiMessages(initializationWithSei("5001" + "900403E80190" + "80")))
        .isEmpty();
  }

  // ITU-T H.265 7.4.2.2: forbidden_zero_bit is 0, the base layer has nuh_layer_id 0, and
  // nuh_temporal_id_plus1 is never 0.
  @ParameterizedTest
  @CsvSource({"CE01, forbidden_zero_bit", "4F01, nuh_layer_id", "4E00, nuh_temporal_id_plus1"})
  @DisplayName("Should refuse an SEI unit when its NAL unit header is one that decoders discard")
  void shouldRefuseAnSeiUnitWhenItsNalUnitHeaderIsOneThatDecodersDiscard(
      String header, String field) {
    var initialization = initializationWithSei(header + "900403E80190" + "80");

    assertThatThrownBy(() -> Fmp4Fixture.hvccSeiMessages(initialization))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(field);
  }

  // A Main 10 initialization segment whose hvcC holds one array with the one SEI unit.
  private static byte[] initializationWithSei(String unitHex) {
    var unit = HexFormat.of().parseHex(unitHex);
    var array =
        ByteBuffer.allocate(5 + unit.length)
            .put((byte) ((Byte.toUnsignedInt(unit[0]) >> 1) & 0x3F))
            .putShort((short) 1)
            .putShort((short) unit.length)
            .put(unit);
    return hevcInitialization("hvc1", box("hvcC", hvcc(MAIN_10_FIELDS, 30, array.array())));
  }

  // Dolby's ISO base media file format specification: profile 8.1 at level 1 with an RPU and a
  // base layer, and the same record with compatibility 6 and compression 3.
  @ParameterizedTest
  @CsvSource({"1, 0, 0100100D10", "6, 3, 0100100D6C"})
  @DisplayName(
      "Should write and read the Dolby Vision record's fields at their bit positions when a sample"
          + " entry holds the record")
  void shouldWriteAndReadTheDolbyVisionRecordsFieldsWhenASampleEntryHoldsTheRecord(
      int compatibilityId, int compression, String leadingBytes) {
    var expected =
        DolbyVisionConfigurationBox.builder()
            .profile(8)
            .level(1)
            .compatibilityId(compatibilityId)
            .compression(compression)
            .build();
    var expectedBox = box("dvvC", HexFormat.of().parseHex(leadingBytes + "00".repeat(19)));

    assertThat(expected.box()).isEqualTo(expectedBox);
    assertThat(
            Fmp4Fixture.dolbyVisionRecords(
                hevcInitialization(
                    "hvc1", box("hvcC", hvcc(MAIN_10_FIELDS, 30, new byte[0])), expectedBox)))
        .containsExactly(expected);
  }

  // An RPU that a muxer moved into the next sample must show at that sample's start, not behind
  // the picture that it describes.
  @Test
  @DisplayName(
      "Should group each sample's NAL unit types by the track run's sample sizes when an RPU starts"
          + " the next sample")
  void shouldGroupEachSamplesNalUnitTypesByTheTrackRunsSampleSizesWhenAnRpuStartsTheNextSample() {
    var media = new ByteArrayOutputStream();
    // trun flags 0x301: a data offset, then a duration and a size for each sample.
    media.writeBytes(moofAheadOfItsMdat(words(0, 1), 0x301, 2, 0, 1, 7, 1, 13));
    media.writeBytes(box("mdat", IDR_UNIT, RPU_UNIT, TRAILING_UNIT));
    // tfhd flags 0x10: every sample of the run takes the default size of 7 bytes.
    media.writeBytes(moofAheadOfItsMdat(words(0x10, 1, 7), 0x001, 1, 0));
    media.writeBytes(box("mdat", IDR_UNIT));

    assertThat(Fmp4Fixture.sampleNalUnitTypes(main10Initialization(), media.toByteArray()))
        .containsExactly(List.of(19), List.of(62, 1), List.of(19));
  }

  @Test
  @DisplayName(
      "Should refuse to read the samples when the track run's data offset points past the mdat")
  void shouldRefuseToReadTheSamplesWhenTheTrackRunsDataOffsetPointsPastTheMdat() {
    var media = new ByteArrayOutputStream();
    media.writeBytes(
        box(
            "moof",
            box("traf", box("tfhd", words(0, 1)), box("trun", words(0x201, 1, 1_000_000, 7)))));
    media.writeBytes(box("mdat", IDR_UNIT));
    var initialization = main10Initialization();
    var segment = media.toByteArray();

    assertThatThrownBy(() -> Fmp4Fixture.sampleNalUnitTypes(initialization, segment))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("data offset");
  }

  // The moof of one track run whose data offset, the third run word, points at the first byte of
  // the mdat that follows.
  private static byte[] moofAheadOfItsMdat(byte[] trackFragmentHeader, int... runWords) {
    var size =
        box("moof", box("traf", box("tfhd", trackFragmentHeader), box("trun", words(runWords))))
            .length;
    runWords[2] = size + 8;
    return box("moof", box("traf", box("tfhd", trackFragmentHeader), box("trun", words(runWords))));
  }

  private static byte[] main10Initialization() {
    return hevcInitialization("hvc1", box("hvcC", hvcc(MAIN_10_FIELDS, 30, new byte[0])));
  }

  // The hvcC fields (ISO/IEC 14496-15 8.3.3.1.2) with 4-byte NAL unit lengths and the arrays.
  private static byte[] hvcc(byte[] profileCompatibilityAndConstraints, int level, byte[] arrays) {
    var hvcC = ByteBuffer.allocate(23 + arrays.length).put((byte) 1);
    hvcC.put(profileCompatibilityAndConstraints).put((byte) level);
    hvcC.position(21);
    hvcC.put((byte) 0x03).put((byte) (arrays.length == 0 ? 0 : 1)).put(arrays);
    return hvcC.array();
  }

  // A video track whose stsd holds the one sample entry with its 78 fixed bytes and the children.
  private static byte[] hevcInitialization(String sampleEntry, byte[]... children) {
    var handlerHeader = ByteBuffer.allocate(25).put(8, "vide".getBytes(StandardCharsets.US_ASCII));
    var entry = new ByteArrayOutputStream();
    entry.writeBytes(new byte[78]);
    for (var child : children) {
      entry.writeBytes(child);
    }

    return box(
        "moov",
        box(
            "trak",
            box(
                "mdia",
                box("hdlr", handlerHeader.array()),
                box(
                    "minf",
                    box(
                        "stbl",
                        box("stsd", words(0, 1), box(sampleEntry, entry.toByteArray())))))));
  }

  private static byte[] track(String handler, int id, int timescale) {
    var trackHeader = ByteBuffer.allocate(84).putInt(12, id).array();
    var mediaHeader = ByteBuffer.allocate(24).putInt(12, timescale).array();
    var handlerHeader = ByteBuffer.allocate(25);
    handlerHeader.position(8);
    handlerHeader.put(handler.getBytes(StandardCharsets.US_ASCII));
    return box(
        "trak",
        box("tkhd", trackHeader),
        box("mdia", box("mdhd", mediaHeader), box("hdlr", handlerHeader.array())));
  }

  private static byte[] box(String type, byte[]... contents) {
    var payload = new ByteArrayOutputStream();
    for (var content : contents) {
      payload.writeBytes(content);
    }

    return ByteBuffer.allocate(8 + payload.size())
        .putInt(8 + payload.size())
        .put(type.getBytes(StandardCharsets.US_ASCII))
        .put(payload.toByteArray())
        .array();
  }

  private static byte[] words(int... values) {
    var bytes = ByteBuffer.allocate(4 * values.length);
    for (var value : values) {
      bytes.putInt(value);
    }

    return bytes.array();
  }
}
