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

  @Test
  @DisplayName("Should return .ts extension for MPEGTS")
  void shouldReturnTsExtensionForMpegts() {
    assertThat(ContainerFormat.MPEGTS.segmentExtension()).isEqualTo(".ts");
  }

  @Test
  @DisplayName("Should return .m4s extension for fMP4")
  void shouldReturnM4sExtensionForFmp4() {
    assertThat(ContainerFormat.FMP4.segmentExtension()).isEqualTo(".m4s");
  }

  @Test
  @DisplayName("Should return HLS version 3 for MPEGTS")
  void shouldReturnHlsVersion3ForMpegts() {
    assertThat(ContainerFormat.MPEGTS.hlsVersion()).isEqualTo(3);
  }

  @Test
  @DisplayName("Should return HLS version 6 for fMP4")
  void shouldReturnHlsVersion6ForFmp4() {
    assertThat(ContainerFormat.FMP4.hlsVersion()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should support all MPEGTS codecs in fMP4")
  void shouldSupportAllMpegtsCodecsInFmp4() {
    assertThat(ContainerFormat.FMP4.supportedAudioCodecs())
        .containsAll(ContainerFormat.MPEGTS.supportedAudioCodecs());
  }
}
