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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A measured copy delivers, in every media segment, a moof header, an mfhd and an mdat header of 32
 * bytes, then for each track a traf, tfhd, tfdt and trun header of 64 bytes and a trun entry of 16
 * bytes for each sample. Over three segments, 1,000,000 video bytes in 216 samples arrive in
 * 1,003,744 bytes.
 */
@Tag("UnitTest")
@DisplayName("Video Copy Bandwidth Tests")
class VideoCopyBandwidthTest {

  private static final CopyMediaPlaylist NINE_SECONDS =
      new CopyMediaPlaylist(3, new BigDecimal("9.000000"));
  private static final long VIDEO_BYTES = 1_000_000;
  private static final long VIDEO_PACKETS = 216;

  // A probe that names no copied stream counts the container bitrate as video, and no audio adds
  // nothing to it.
  @Test
  @DisplayName("Should cost the container bitrate when the session delivers no audio")
  void shouldCostTheContainerBitrateWhenTheSessionDeliversNoAudio() {
    var source = defaultProbeBuilder().bitrate(4_000_000L).build();

    assertThat(
            VideoCopyBandwidth.averageBandwidth(
                source, AudioDecision.none(), CopyMediaPlaylist.UNKNOWN))
        .isEqualTo(4_000_000L);
  }

  // 1,003,744 bytes are 8,029,952 bits, which average 892,216.9 b/s over 9 s.
  @Test
  @DisplayName(
      "Should count the video's fragment framing and round up when the session delivers no audio")
  void shouldCountVideosFragmentFramingAndRoundUpWhenSessionDeliversNoAudio() {
    var source = measuredSource(List.of()).build();

    assertThat(VideoCopyBandwidth.averageBandwidth(source, AudioDecision.none(), NINE_SECONDS))
        .isEqualTo(892_217L);
  }

  // The first audio stream adds 100,000 bytes, 64 bytes in each of the three segments and 16 bytes
  // for each of its 422 samples: 1,110,688 bytes, which average 987,278.2 b/s over 9 s.
  @Test
  @DisplayName(
      "Should count the first audio stream's bytes and framing when the session copies the audio")
  void shouldCountFirstAudioStreamsBytesAndFramingWhenSessionCopiesAudio() {
    var probe = measuredSource(List.of());
    var video = ProbeFixture.videoStreamBuilder(probe.build()).build();
    var first = ProbeFixture.audioStreamBuilder("ac3").index(video.index() + 1).build();
    var second = ProbeFixture.audioStreamBuilder("ac3").index(video.index() + 2).build();
    var source =
        measuredSource(
                List.of(
                    measuredStream(first.index(), 100_000, 422),
                    measuredStream(second.index(), 4_500_000_000L, 1)))
            .streams(List.of(video, first, second))
            .build();

    assertThat(VideoCopyBandwidth.averageBandwidth(source, ac3Copy(), NINE_SECONDS))
        .isEqualTo(987_279L);
  }

  // The encoder writes ceil((9 s * rate + priming) / frame size) frames: AAC 1,024 priming samples
  // in frames of 1,024 and AC-3 and E-AC-3 256 in frames of 1,536. Each frame's 16 bytes and the
  // track's 64 bytes in each segment count as framing, and the session's bitrate as the payload.
  // The rate is the lowest rate that the encoder takes at or above the source's, or its highest
  // rate when the source's is above it or unknown.
  @ParameterizedTest(name = "{0} at {1} Hz")
  @CsvSource({
    "aac, 48000, 128000, 1026404",
    "aac, 44100, 128000, 1025920",
    "aac, 50000, 128000, 1028409",
    "aac, 192000, 128000, 1032406",
    "aac, 0, 128000, 1032406",
    "ac3, 44100, 640000, 1536072",
    "eac3, 96000, 640000, 1536399"
  })
  @DisplayName(
      "Should count the encoded audio's frames as framing and its bitrate as payload when the"
          + " session encodes the audio")
  void shouldCountEncodedAudiosFramesAsFramingAndBitrateAsPayloadWhenSessionEncodesAudio(
      String codec, int sampleRate, long bitrate, long expected) {
    var probe = measuredSource(List.of());
    var video = ProbeFixture.videoStreamBuilder(probe.build()).build();
    var audio =
        ProbeFixture.audioStreamBuilder("truehd")
            .index(video.index() + 1)
            .sampleRate(sampleRate == 0 ? OptionalInt.empty() : OptionalInt.of(sampleRate))
            .build();
    var source = probe.streams(List.of(video, audio)).build();
    var encoded = AudioDecision.stereoAac().toBuilder().codec(codec).bitrate(bitrate).build();

    assertThat(VideoCopyBandwidth.averageBandwidth(source, encoded, NINE_SECONDS))
        .isEqualTo(expected);
  }

  // Three segments of 3.000000, 3.000000 and 2.999999 s sum to 8.999999 s. The 1,125,000 delivered
  // bytes are 9,000,000 bits.
  @ParameterizedTest(name = "{0} s")
  @CsvSource({"9.000000, 1000000", "8.999999, 1000001"})
  @DisplayName("Should divide by the EXTINF durations that the media playlist writes")
  void shouldDivideByExtinfDurationsThatMediaPlaylistWrites(String seconds, long expected) {
    var source = measuredSource(List.of(), 1_121_256).build();

    assertThat(
            VideoCopyBandwidth.averageBandwidth(
                source, AudioDecision.none(), new CopyMediaPlaylist(3, new BigDecimal(seconds))))
        .isEqualTo(expected);
  }

  @Test
  @DisplayName("Should keep the unmeasured cost when a stream copy cannot list the media playlist")
  void shouldKeepUnmeasuredCostWhenStreamCopyCannotListMediaPlaylist() {
    var source = measuredSource(List.of()).build();

    assertThat(
            VideoCopyBandwidth.averageBandwidth(
                source, AudioDecision.none(), CopyMediaPlaylist.UNKNOWN))
        .isEqualTo(4_000_000L);
    assertThat(
            VideoCopyBandwidth.averageBandwidth(
                source, AudioDecision.none(), new CopyMediaPlaylist(3, BigDecimal.ZERO)))
        .isEqualTo(4_000_000L);
  }

  @Test
  @DisplayName("Should keep the unmeasured cost when the server encodes audio of another codec")
  void shouldKeepUnmeasuredCostWhenServerEncodesAudioOfAnotherCodec() {
    var source = measuredSource(List.of()).build();
    var opus = AudioDecision.stereoAac().toBuilder().codec("opus").build();

    assertThat(VideoCopyBandwidth.averageBandwidth(source, opus, NINE_SECONDS))
        .isEqualTo(4_128_000L);
  }

  @Test
  @DisplayName("Should keep the unmeasured cost when the measurement holds no copied video stream")
  void shouldKeepUnmeasuredCostWhenMeasurementHoldsNoCopiedVideoStream() {
    var source =
        defaultProbeBuilder()
            .bitrate(4_000_000L)
            .streamMeasurements(
                Optional.of(measurementOf(List.of(measuredStream(99, VIDEO_BYTES, 1)))))
            .build();

    assertThat(VideoCopyBandwidth.averageBandwidth(source, AudioDecision.none(), NINE_SECONDS))
        .isEqualTo(4_000_000L);
  }

  @Test
  @DisplayName("Should keep the unmeasured cost when the measurement holds no copied audio stream")
  void shouldKeepUnmeasuredCostWhenMeasurementHoldsNoCopiedAudioStream() {
    var probe = measuredSource(List.of());
    var video = ProbeFixture.videoStreamBuilder(probe.build()).build();
    var audio = ProbeFixture.audioStreamBuilder("ac3").index(video.index() + 1).build();

    assertThat(
            VideoCopyBandwidth.averageBandwidth(
                probe.streams(List.of(video, audio)).build(), ac3Copy(), NINE_SECONDS))
        .isEqualTo(4_000_000L);
  }

  @Test
  @DisplayName("Should keep the unmeasured cost when the measured video holds no bytes")
  void shouldKeepUnmeasuredCostWhenMeasuredVideoHoldsNoBytes() {
    var source = measuredSource(List.of(), 0).build();
    var withoutBitrate = measuredSource(List.of(), 0).bitrate(0).build();

    assertThat(VideoCopyBandwidth.averageBandwidth(source, AudioDecision.none(), NINE_SECONDS))
        .isEqualTo(4_000_000L);
    assertThat(VideoCopyBandwidth.isKnown(withoutBitrate, NINE_SECONDS)).isFalse();
  }

  @Test
  @DisplayName(
      "Should cost at most the largest bit rate when the measured bytes exceed what a bit rate can"
          + " count")
  void shouldCostAtMostLargestBitRateWhenMeasuredBytesExceedWhatBitRateCanCount() {
    var source = measuredSource(List.of(), Long.MAX_VALUE).build();

    assertThat(
            VideoCopyBandwidth.averageBandwidth(
                source, AudioDecision.stereoAac(), new CopyMediaPlaylist(1, BigDecimal.ONE)))
        .isEqualTo(Long.MAX_VALUE);
  }

  @Test
  @DisplayName(
      "Should know a copy's cost when the probe knows no bitrate only while the server measured the"
          + " copied video and a stream copy lists the media playlist")
  void shouldKnowCopysCostWithoutBitrateOnlyWhileServerMeasuredVideoAndStreamCopyListsPlaylist() {
    var source = measuredSource(List.of()).bitrate(0).build();

    assertThat(VideoCopyBandwidth.isKnown(source, NINE_SECONDS)).isTrue();
    assertThat(VideoCopyBandwidth.isKnown(source, CopyMediaPlaylist.UNKNOWN)).isFalse();
  }

  @Test
  @DisplayName("Should refuse to cost a copy when the source, the audio or the playlist is missing")
  void shouldRefuseToCostACopyWhenTheSourceTheAudioOrThePlaylistIsMissing() {
    var source = defaultProbeBuilder().build();
    var audio = AudioDecision.stereoAac();
    var playlist = CopyMediaPlaylist.UNKNOWN;

    assertThatNullPointerException()
        .isThrownBy(() -> VideoCopyBandwidth.averageBandwidth(null, audio, playlist))
        .withMessageContaining("source");
    assertThatNullPointerException()
        .isThrownBy(() -> VideoCopyBandwidth.averageBandwidth(source, null, playlist))
        .withMessageContaining("audio");
    assertThatNullPointerException()
        .isThrownBy(() -> VideoCopyBandwidth.averageBandwidth(source, audio, null))
        .withMessageContaining("playlist");
    assertThatNullPointerException()
        .isThrownBy(() -> VideoCopyBandwidth.isKnown(null, playlist))
        .withMessageContaining("source");
    assertThatNullPointerException()
        .isThrownBy(() -> VideoCopyBandwidth.isKnown(source, null))
        .withMessageContaining("playlist");
  }

  /**
   * A source of 4 Mb/s whose copied video the server measured at 1,000,000 bytes in 216 packets,
   * beside these other measured streams.
   */
  private static MediaProbe.MediaProbeBuilder measuredSource(List<MeasuredStream> others) {
    return measuredSource(others, VIDEO_BYTES);
  }

  private static MediaProbe.MediaProbeBuilder measuredSource(
      List<MeasuredStream> others, long videoBytes) {
    var probe = defaultProbeBuilder().bitrate(4_000_000L);
    var videoIndex = ProbeFixture.videoStreamBuilder(probe.build()).build().index();
    var streams = new ArrayList<MeasuredStream>();
    streams.add(measuredStream(videoIndex, videoBytes, VIDEO_PACKETS));
    streams.addAll(others);
    return probe.streamMeasurements(Optional.of(measurementOf(streams)));
  }

  private static AudioDecision ac3Copy() {
    return AudioDecision.builder()
        .mode(AudioMode.COPY)
        .codec("ac3")
        .channels(6)
        .bitrate(640_000L)
        .sourceCodecString(Optional.of("ac-3"))
        .build();
  }

  private static MeasuredStream measuredStream(int streamIndex, long measuredBytes, long packets) {
    return MeasuredStream.builder()
        .streamIndex(streamIndex)
        .measuredBytes(measuredBytes)
        .packetCount(packets)
        .timeBaseNumerator(1)
        .timeBaseDenominator(1_000)
        .measuredDuration(1_000)
        .keyframeIntervalBytes(KeyframeIntervalCounts.of(0))
        .keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(packets))
        .build();
  }

  private static StreamMeasurements measurementOf(List<MeasuredStream> streams) {
    return new StreamMeasurements(StreamMeasurementSource.SAMPLE_TABLE, streams);
  }
}
