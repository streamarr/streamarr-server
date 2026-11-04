package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Source Container Tests")
class SourceContainerTest {

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
    assertThat(SourceContainer.ofProbedFormat(demuxer)).contains(container);
  }

  @Test
  @DisplayName("Should name no source container when the demuxer reads MP4 and MOV alike")
  void shouldNameNoSourceContainerWhenTheDemuxerReadsMp4AndMovAlike() {
    assertThat(SourceContainer.ofProbedFormat("mov,mp4,m4a,3gp,3g2,mj2")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "wav", "matroska", "MPEGTS", "hls"})
  @DisplayName("Should name no source container when the demuxer is not a known video container")
  void shouldNameNoSourceContainerWhenTheDemuxerIsNotAKnownVideoContainer(String demuxer) {
    assertThat(SourceContainer.ofProbedFormat(demuxer)).isEmpty();
  }
}
