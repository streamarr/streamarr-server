package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Container Format Tests")
class ContainerFormatTest {

  @Test
  @DisplayName("Should support aac, ac3, eac3 and mp3 in MPEGTS")
  void shouldSupportAacAc3Eac3AndMp3InMpegts() {
    assertThat(ContainerFormat.MPEGTS.supportedAudioCodecs())
        .containsExactlyInAnyOrder("aac", "ac3", "eac3", "mp3");
  }

  @Test
  @DisplayName("Should support aac, ac3, eac3, mp3, flac, opus and alac in fMP4")
  void shouldSupportExtendedCodecsInFmp4() {
    assertThat(ContainerFormat.FMP4.supportedAudioCodecs())
        .containsExactlyInAnyOrder("aac", "ac3", "eac3", "mp3", "flac", "opus", "alac");
  }
}
