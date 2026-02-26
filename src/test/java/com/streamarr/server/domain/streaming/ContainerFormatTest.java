package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Container Format Tests")
class ContainerFormatTest {

  @Test
  @DisplayName("Should support aac, ac3, eac3 and mp3 when container is MPEGTS")
  void shouldSupportAacAc3Eac3AndMp3WhenContainerIsMpegts() {
    assertThat(ContainerFormat.MPEGTS.supportedAudioCodecs())
        .containsExactlyInAnyOrder("aac", "ac3", "eac3", "mp3");
  }

  @Test
  @DisplayName("Should support extended codecs when container is fMP4")
  void shouldSupportExtendedCodecsWhenContainerIsFmp4() {
    assertThat(ContainerFormat.FMP4.supportedAudioCodecs())
        .containsExactlyInAnyOrder("aac", "ac3", "eac3", "mp3", "flac", "opus", "alac");
  }

  @Test
  @DisplayName("Should return .ts extension when container is MPEGTS")
  void shouldReturnTsExtensionWhenContainerIsMpegts() {
    assertThat(ContainerFormat.MPEGTS.segmentExtension()).isEqualTo(".ts");
  }

  @Test
  @DisplayName("Should return .m4s extension when container is fMP4")
  void shouldReturnM4sExtensionWhenContainerIsFmp4() {
    assertThat(ContainerFormat.FMP4.segmentExtension()).isEqualTo(".m4s");
  }

  @Test
  @DisplayName("Should return HLS version 3 when container is MPEGTS")
  void shouldReturnHlsVersion3WhenContainerIsMpegts() {
    assertThat(ContainerFormat.MPEGTS.hlsVersion()).isEqualTo(3);
  }

  @Test
  @DisplayName("Should return HLS version 6 when container is fMP4")
  void shouldReturnHlsVersion6WhenContainerIsFmp4() {
    assertThat(ContainerFormat.FMP4.hlsVersion()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should support all MPEGTS codecs when container is fMP4")
  void shouldSupportAllMpegtsCodecsWhenContainerIsFmp4() {
    assertThat(ContainerFormat.FMP4.supportedAudioCodecs())
        .containsAll(ContainerFormat.MPEGTS.supportedAudioCodecs());
  }
}
