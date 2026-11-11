package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Keyframes unavailable reason")
class KeyframesUnavailableReasonTest {

  @ParameterizedTest
  @CsvSource({
    "PACKET_SCAN_MEDIA_ERROR, true",
    "PACKET_SCAN_FAILED, true",
    "KEYFRAME_WITHOUT_TIMESTAMP, false",
    "NO_KEYFRAMES, false"
  })
  @DisplayName(
      "Should name a failed packet scan only when the scan ended without reading the stream's"
          + " keyframes")
  void shouldNameFailedPacketScanOnlyWhenScanEndedWithoutReadingStreamsKeyframes(
      KeyframesUnavailableReason reason, boolean failedPacketScan) {
    assertThat(reason.isFailedPacketScan()).isEqualTo(failedPacketScan);
  }
}
