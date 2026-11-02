package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.streaming.AudioDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Video Copy Bandwidth Tests")
class VideoCopyBandwidthTest {

  // A probe that names no copied stream counts the container bitrate as video, and no audio adds
  // nothing to it.
  @Test
  @DisplayName("Should cost the container bitrate when the session delivers no audio")
  void shouldCostTheContainerBitrateWhenTheSessionDeliversNoAudio() {
    var source = defaultProbeBuilder().bitrate(4_000_000L).build();

    assertThat(VideoCopyBandwidth.averageBandwidth(source, AudioDecision.none()))
        .isEqualTo(4_000_000L);
  }

  @Test
  @DisplayName("Should refuse to cost a copy when the source or the audio is missing")
  void shouldRefuseToCostACopyWhenTheSourceOrTheAudioIsMissing() {
    var source = defaultProbeBuilder().build();
    var audio = AudioDecision.stereoAac();

    assertThatNullPointerException()
        .isThrownBy(() -> VideoCopyBandwidth.averageBandwidth(null, audio))
        .withMessageContaining("source");
    assertThatNullPointerException()
        .isThrownBy(() -> VideoCopyBandwidth.averageBandwidth(source, null))
        .withMessageContaining("audio");
    assertThatNullPointerException()
        .isThrownBy(() -> VideoCopyBandwidth.isKnown(null))
        .withMessageContaining("source");
  }
}
