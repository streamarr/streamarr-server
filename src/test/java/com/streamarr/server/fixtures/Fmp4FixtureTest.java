package com.streamarr.server.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Fragmented MP4 Fixture Tests")
class Fmp4FixtureTest {

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
