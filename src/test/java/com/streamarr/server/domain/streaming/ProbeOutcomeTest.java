package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe outcome contracts")
class ProbeOutcomeTest {

  @Test
  @DisplayName(
      "Should preserve ordered streams and first-track summary when the source list changes")
  void shouldPreserveOrderedStreamsAndFirstTrackSummaryWhenTheSourceListChanges() {
    var firstVideo = streamBuilder("video", "h264").index(0).build();
    var firstAudio = streamBuilder("audio", "ac3").index(1).build();
    var laterVideo = streamBuilder("video", "hevc").index(2).isDefault(true).build();
    var laterAudio = streamBuilder("audio", "aac").index(3).isDefault(true).build();
    var source = new ArrayList<>(List.of(firstVideo, firstAudio, laterVideo, laterAudio));
    var outcome = new ProbeOutcome.Success(ProbeContainer.builder().build(), source);
    var summary = outcome.mediaProbe();

    source.clear();
    source.add(laterVideo);
    source.add(laterAudio);

    assertThat(outcome.streams()).containsExactly(firstVideo, firstAudio, laterVideo, laterAudio);
    assertThat(outcome.mediaProbe()).isEqualTo(summary);
    assertThat(outcome.mediaProbe().videoCodec()).isEqualTo("h264");
    assertThat(outcome.mediaProbe().audioCodec()).isEqualTo("ac3");
  }

  @Test
  @DisplayName("Should reject changes when a caller mutates the retained streams")
  void shouldRejectChangesWhenACallerMutatesTheRetainedStreams() {
    var video = streamBuilder("video", "h264").build();
    var outcome = new ProbeOutcome.Success(ProbeContainer.builder().build(), List.of(video));
    var streams = outcome.streams();

    assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(streams::clear);

    assertThat(outcome.streams()).containsExactly(video);
  }

  @Test
  @DisplayName("Should reject a success when its container is missing")
  void shouldRejectASuccessWhenItsContainerIsMissing() {
    var streams = List.of(streamBuilder("video", "h264").build());

    assertThatNullPointerException().isThrownBy(() -> new ProbeOutcome.Success(null, streams));
  }

  @Test
  @DisplayName("Should reject a success when its stream list is missing")
  void shouldRejectASuccessWhenItsStreamListIsMissing() {
    var container = ProbeContainer.builder().build();

    assertThatNullPointerException().isThrownBy(() -> new ProbeOutcome.Success(container, null));
  }

  @Test
  @DisplayName("Should reject a success when a stream entry is missing")
  void shouldRejectASuccessWhenAStreamEntryIsMissing() {
    var container = ProbeContainer.builder().build();
    var streams = new ArrayList<StreamInfo>();
    streams.add(streamBuilder("video", "h264").build());
    streams.add(null);

    assertThatNullPointerException().isThrownBy(() -> new ProbeOutcome.Success(container, streams));
  }

  @Test
  @DisplayName("Should reject a failure when its terminal error is missing")
  void shouldRejectAFailureWhenItsTerminalErrorIsMissing() {
    assertThatNullPointerException().isThrownBy(() -> new ProbeOutcome.Failure(null));
  }

  private StreamInfo.StreamInfoBuilder streamBuilder(String codecType, String codec) {
    return StreamInfo.builder().codecType(codecType).codec(Optional.of(codec));
  }
}
