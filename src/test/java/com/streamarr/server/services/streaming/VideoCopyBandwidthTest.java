package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.MeasuredStream;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.fixtures.ProbeFixture;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
  @DisplayName(
      "Should round the measured average up to a whole bit per second when the bytes do not divide"
          + " evenly")
  void shouldRoundMeasuredAverageUpWhenBytesDoNotDivideEvenly() {
    var source = measuredSource(1).duration(Duration.ofSeconds(3)).build();

    assertThat(VideoCopyBandwidth.averageBandwidth(source, AudioDecision.none())).isEqualTo(3);
  }

  @Test
  @DisplayName("Should keep the unmeasured cost when the media playlist has no duration")
  void shouldKeepUnmeasuredCostWhenMediaPlaylistHasNoDuration() {
    var source = measuredSource(1_000_000).duration(Duration.ZERO).build();

    assertThat(VideoCopyBandwidth.averageBandwidth(source, AudioDecision.none()))
        .isEqualTo(4_000_000L);
  }

  @Test
  @DisplayName("Should keep the unmeasured cost when the measurement holds no copied video stream")
  void shouldKeepUnmeasuredCostWhenMeasurementHoldsNoCopiedVideoStream() {
    var source =
        defaultProbeBuilder()
            .bitrate(4_000_000L)
            .streamMeasurements(Optional.of(measurementOf(measuredStream(99, 1_000_000))))
            .build();

    assertThat(VideoCopyBandwidth.averageBandwidth(source, AudioDecision.none()))
        .isEqualTo(4_000_000L);
  }

  @Test
  @DisplayName(
      "Should add the session's audio bitrate when the measurement holds no copied audio stream")
  void shouldAddSessionsAudioBitrateWhenMeasurementHoldsNoCopiedAudioStream() {
    var probe = measuredSource(900_000_000);
    var video = ProbeFixture.videoStreamBuilder(probe.build()).build();
    var audio = ProbeFixture.audioStreamBuilder("ac3").index(video.index() + 1).build();
    var copy =
        AudioDecision.builder()
            .mode(AudioMode.COPY)
            .codec("ac3")
            .channels(6)
            .bitrate(640_000L)
            .sourceCodecString(Optional.of("ac-3"))
            .build();

    assertThat(
            VideoCopyBandwidth.averageBandwidth(probe.streams(List.of(video, audio)).build(), copy))
        .isEqualTo(1_640_000L);
  }

  @Test
  @DisplayName(
      "Should cost at most the largest bit rate when the measured bytes exceed what a bit rate can"
          + " count")
  void shouldCostAtMostLargestBitRateWhenMeasuredBytesExceedWhatBitRateCanCount() {
    var source = measuredSource(Long.MAX_VALUE).duration(Duration.ofSeconds(1)).build();

    assertThat(VideoCopyBandwidth.averageBandwidth(source, AudioDecision.stereoAac()))
        .isEqualTo(Long.MAX_VALUE);
  }

  @Test
  @DisplayName(
      "Should know a copy's cost when the server measured the copied video and the probe knows no"
          + " bitrate")
  void shouldKnowCopysCostWhenServerMeasuredCopiedVideoAndProbeKnowsNoBitrate() {
    var source = measuredSource(1_000_000).bitrate(0).build();

    assertThat(VideoCopyBandwidth.isKnown(source)).isTrue();
  }

  /**
   * A source of 4 Mb/s whose copied video the server measured at {@code videoBytes} over the
   * default media playlist of 2 hours.
   */
  private static MediaProbe.MediaProbeBuilder measuredSource(long videoBytes) {
    var probe = defaultProbeBuilder().bitrate(4_000_000L);
    var videoIndex = ProbeFixture.videoStreamBuilder(probe.build()).build().index();
    return probe.streamMeasurements(
        Optional.of(measurementOf(measuredStream(videoIndex, videoBytes))));
  }

  private static MeasuredStream measuredStream(int streamIndex, long measuredBytes) {
    return MeasuredStream.builder()
        .streamIndex(streamIndex)
        .measuredBytes(measuredBytes)
        .packetCount(1)
        .timeBaseNumerator(1)
        .timeBaseDenominator(1_000)
        .measuredDuration(1_000)
        .keyframeIntervalBytes(KeyframeIntervalCounts.of(0))
        .keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(1))
        .build();
  }

  private static StreamMeasurements measurementOf(MeasuredStream stream) {
    return new StreamMeasurements(StreamMeasurementSource.SAMPLE_TABLE, List.of(stream));
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
