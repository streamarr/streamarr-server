package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Source Container Tests")
class SourceContainerTest {

  // ffprobe's one demuxer for MP4, M4V, 3GP and MOV files.
  private static final String MOV_DEMUXER = "mov,mp4,m4a,3gp,3g2,mj2";

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "matroska,webm | MATROSKA",
        "mpegts | MPEG_TS",
        "mpeg | MPEG_PS",
        "avi | AVI",
        "asf | ASF",
        "flv | FLV"
      })
  @DisplayName("Should name the source container when the probe reports its demuxer")
  void shouldNameTheSourceContainerWhenTheProbeReportsItsDemuxer(
      String demuxer, SourceContainer container) {
    assertThat(SourceContainer.ofProbedFormat(demuxer, Optional.empty())).contains(container);
  }

  // QuickTime's major brand makes the source container MOV (ADR 0045).
  @Test
  @DisplayName("Should name MOV when the major brand is QuickTime's")
  void shouldNameMovWhenTheMajorBrandIsQuickTimes() {
    assertThat(SourceContainer.ofProbedFormat(MOV_DEMUXER, Optional.of("qt  ")))
        .contains(SourceContainer.MOV);
  }

  // Every other major brand makes the source container MP4, among them the MPEG-4, M4V and 3GP
  // brands (ADR 0045). The stored brand keeps its trailing spaces, and brands are case-sensitive.
  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(
      strings = {
        "isom", "iso2", "iso6", "mp41", "mp42", "avc1", "M4V ", "M4VH", "M4VP", "3gp4", "3gp6",
        "3g2a", "dash", "XAVC", "QT  "
      })
  @DisplayName("Should name MP4 when the major brand is not QuickTime's")
  void shouldNameMp4WhenTheMajorBrandIsNotQuickTimes(String majorBrand) {
    assertThat(SourceContainer.ofProbedFormat(MOV_DEMUXER, Optional.of(majorBrand)))
        .contains(SourceContainer.MP4);
  }

  // ISO/IEC 14496-12 reads a file without an ftyp box as the brand mp41 (ADR 0045).
  @Test
  @DisplayName("Should name MP4 when the file has no major brand")
  void shouldNameMp4WhenTheFileHasNoMajorBrand() {
    assertThat(SourceContainer.ofProbedFormat(MOV_DEMUXER, Optional.empty()))
        .contains(SourceContainer.MP4);
  }

  @Test
  @DisplayName("Should keep the demuxer's source container when the probe also names a brand")
  void shouldKeepTheDemuxersSourceContainerWhenTheProbeAlsoNamesABrand() {
    assertThat(SourceContainer.ofProbedFormat("matroska,webm", Optional.of("qt  ")))
        .contains(SourceContainer.MATROSKA);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "wav", "matroska", "MPEGTS", "hls", "mov,mp4"})
  @DisplayName("Should name no source container when the demuxer is not a known video container")
  void shouldNameNoSourceContainerWhenTheDemuxerIsNotAKnownVideoContainer(String demuxer) {
    assertThat(SourceContainer.ofProbedFormat(demuxer, Optional.empty())).isEmpty();
  }
}
