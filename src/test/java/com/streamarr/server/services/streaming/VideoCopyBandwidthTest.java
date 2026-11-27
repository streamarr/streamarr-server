package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MeasuredStream;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.fixtures.ProbeFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

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
      CopyMediaPlaylistFixture.evenlyListing(3, "3.000000");
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
                    measuredStream(first.index()).measuredBytes(100_000).packetCount(422).build(),
                    measuredStream(second.index())
                        .measuredBytes(4_500_000_000L)
                        .packetCount(1)
                        .build()))
            .streams(List.of(video, first, second))
            .build();

    assertThat(VideoCopyBandwidth.averageBandwidth(source, ac3Copy(), NINE_SECONDS))
        .isEqualTo(987_279L);
  }

  // The encoder writes ceil((9 s * rate + priming) / frame size) frames: AAC 1,024 priming samples
  // in frames of 1,024 and AC-3 and E-AC-3 256 in frames of 1,536. Each frame's 16 bytes and the
  // track's 64 bytes in each segment count as framing, and the session's bitrate as the payload.
  @ParameterizedTest(name = "{0}")
  @MethodSource("sourceRatesThatAnEncoderTakesOrRoundsUpTo")
  @DisplayName(
      "Should count the encoded audio's frames at the lowest rate that the encoder takes at or"
          + " above the source's rate when the session encodes the audio")
  void shouldCountEncodedAudiosFramesAtLowestRateEncoderTakesAtOrAboveSourcesRate(
      EncodedAudioCost expected) {
    assertThat(encodedAudioCost(expected)).isEqualTo(expected.cost());
  }

  // AAC at 48 kHz writes 423 frames, at 44.1 kHz 389, and a 50 kHz source takes 64 kHz, 564
  // frames. AC-3 at 44.1 kHz writes 259 frames.
  static Stream<EncodedAudioCost> sourceRatesThatAnEncoderTakesOrRoundsUpTo() {
    return Stream.of(
        aacAt(OptionalInt.of(48_000)).cost(1_026_404L).build(),
        aacAt(OptionalInt.of(44_100)).cost(1_025_920L).build(),
        aacAt(OptionalInt.of(50_000)).cost(1_028_409L).build(),
        EncodedAudioCost.builder()
            .codec("ac3")
            .sourceRate(OptionalInt.of(44_100))
            .bitrate(640_000L)
            .cost(1_536_072L)
            .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sourceRatesAboveEveryRateThatAnEncoderTakesOrUnknown")
  @DisplayName(
      "Should count the encoded audio's frames at the encoder's highest rate when the source's rate"
          + " is above every rate that the encoder takes or unknown")
  void shouldCountEncodedAudiosFramesAtEncodersHighestRateWhenSourcesRateIsAboveItOrUnknown(
      EncodedAudioCost expected) {
    assertThat(encodedAudioCost(expected)).isEqualTo(expected.cost());
  }

  // AAC at its highest rate of 96 kHz writes 845 frames, and E-AC-3 at its highest rate of 48 kHz
  // 282 frames.
  static Stream<EncodedAudioCost> sourceRatesAboveEveryRateThatAnEncoderTakesOrUnknown() {
    return Stream.of(
        aacAt(OptionalInt.of(192_000)).cost(1_032_406L).build(),
        aacAt(OptionalInt.empty()).cost(1_032_406L).build(),
        EncodedAudioCost.builder()
            .codec("eac3")
            .sourceRate(OptionalInt.of(96_000))
            .bitrate(640_000L)
            .cost(1_536_399L)
            .build());
  }

  private static EncodedAudioCost.EncodedAudioCostBuilder aacAt(OptionalInt sourceRate) {
    return EncodedAudioCost.builder().codec("aac").sourceRate(sourceRate).bitrate(128_000L);
  }

  // The source's first audio stream is a TrueHD track at the case's rate.
  private static long encodedAudioCost(EncodedAudioCost encode) {
    var probe = measuredSource(List.of());
    var video = ProbeFixture.videoStreamBuilder(probe.build()).build();
    var audio =
        ProbeFixture.audioStreamBuilder("truehd")
            .index(video.index() + 1)
            .sampleRate(encode.sourceRate())
            .build();
    var source = probe.streams(List.of(video, audio)).build();
    var encoded =
        AudioDecision.stereoAac().toBuilder()
            .codec(encode.codec())
            .bitrate(encode.bitrate())
            .build();
    return VideoCopyBandwidth.averageBandwidth(source, encoded, NINE_SECONDS);
  }

  @Builder
  record EncodedAudioCost(String codec, OptionalInt sourceRate, long bitrate, long cost) {}

  // Three segments of 3.000000, 3.000000 and 2.999999 s sum to 8.999999 s. The 1,125,000 delivered
  // bytes are 9,000,000 bits.
  @ParameterizedTest(name = "last segment {0} s")
  @CsvSource({"3.000000, 1000000", "2.999999, 1000001"})
  @DisplayName("Should divide by the EXTINF durations that the media playlist writes")
  void shouldDivideByExtinfDurationsThatMediaPlaylistWrites(String lastSeconds, long expected) {
    var source = measuredSource(List.of(), 1_121_256).build();
    var playlist = CopyMediaPlaylistFixture.listing(3, "3.000000", "3.000000", lastSeconds);

    assertThat(VideoCopyBandwidth.averageBandwidth(source, AudioDecision.none(), playlist))
        .isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("playlistsWithoutASegmentOrADuration")
  @DisplayName("Should keep the unmeasured cost when a stream copy cannot list the media playlist")
  void shouldKeepUnmeasuredCostWhenStreamCopyCannotListMediaPlaylist(CopyMediaPlaylist playlist) {
    var source = measuredSource(List.of()).build();

    assertThat(VideoCopyBandwidth.averageBandwidth(source, AudioDecision.none(), playlist))
        .isEqualTo(4_000_000L);
  }

  // A file of 1 GiB holds more than the 128 MiB segment cap, but a playlist without a segment or a
  // duration gives no rate to estimate a segment by.
  @ParameterizedTest(name = "{0}")
  @MethodSource("playlistsWithoutASegmentOrADuration")
  @DisplayName(
      "Should estimate no segment over the segment cap when a stream copy cannot list the media"
          + " playlist")
  void shouldEstimateNoSegmentOverTheSegmentCapWhenAStreamCopyCannotListTheMediaPlaylist(
      CopyMediaPlaylist playlist) {
    var source =
        defaultProbeBuilder()
            .bitrate(0)
            .sourceSnapshot(Optional.of(new SourceFileSnapshot(1L << 30, Instant.EPOCH)))
            .build();

    assertThat(VideoCopyBandwidth.estimateExceedsSegmentCap(source, AudioDecision.none(), playlist))
        .isFalse();
  }

  static Stream<CopyMediaPlaylist> playlistsWithoutASegmentOrADuration() {
    return Stream.of(
        CopyMediaPlaylist.UNKNOWN,
        CopyMediaPlaylistFixture.listing(3, "0", "0", "0"),
        new CopyMediaPlaylist(List.of(), 3, new Fraction(1, 1)));
  }

  // The server knows the framing of only the AAC, AC-3 and E-AC-3 encoders.
  @ParameterizedTest(name = "{0}")
  @MethodSource("encodesWhoseFramingTheServerDoesNotKnow")
  @DisplayName(
      "Should keep the unmeasured cost when the session encodes audio whose framing the server"
          + " does not know")
  void shouldKeepUnmeasuredCostWhenSessionEncodesAudioWhoseFramingServerDoesNotKnow(
      AudioDecision encoded) {
    var source = measuredSource(List.of()).build();

    assertThat(VideoCopyBandwidth.averageBandwidth(source, encoded, NINE_SECONDS))
        .isEqualTo(4_128_000L);
  }

  static Stream<AudioDecision> encodesWhoseFramingTheServerDoesNotKnow() {
    var encode = AudioDecision.stereoAac().toBuilder();
    return Stream.of(encode.codec("opus").build(), encode.codec(null).build());
  }

  @Test
  @DisplayName("Should keep the unmeasured cost when the measurement holds no copied video stream")
  void shouldKeepUnmeasuredCostWhenMeasurementHoldsNoCopiedVideoStream() {
    var source =
        defaultProbeBuilder()
            .bitrate(4_000_000L)
            .streamMeasurements(
                Optional.of(
                    measurementOf(
                        List.of(
                            measuredStream(99).measuredBytes(VIDEO_BYTES).packetCount(1).build()))))
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

    assertThat(VideoCopyBandwidth.averageBandwidth(source, AudioDecision.none(), NINE_SECONDS))
        .isEqualTo(4_000_000L);
  }

  @Test
  @DisplayName(
      "Should not know a copy's cost when the measured video holds no bytes and the probe knows no"
          + " bitrate")
  void shouldNotKnowCopysCostWhenMeasuredVideoHoldsNoBytesAndProbeKnowsNoBitrate() {
    var source = measuredSource(List.of(), 0).bitrate(0).build();

    assertThat(VideoCopyBandwidth.isKnown(source, NINE_SECONDS)).isFalse();
  }

  @Test
  @DisplayName(
      "Should cost at most the largest bit rate when the measured bytes exceed what a bit rate can"
          + " count")
  void shouldCostAtMostLargestBitRateWhenMeasuredBytesExceedWhatBitRateCanCount() {
    var source = measuredSource(List.of(), Long.MAX_VALUE).build();

    assertThat(
            VideoCopyBandwidth.averageBandwidth(
                source, AudioDecision.stereoAac(), CopyMediaPlaylistFixture.listing(1, "1.000000")))
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

  // ADR 0054 computes a copy's peak from its measured keyframe intervals, which follow the
  // source's keyframe positions. Without those positions the copy keeps 1.2 times its average.
  @Test
  @DisplayName("Should give no peak when the source's keyframe positions are not known")
  void shouldGiveNoPeakWhenTheSourcesKeyframePositionsAreNotKnown() {
    var source = measuredSource(List.of()).keyframes(new KeyframeKnowledge.NotReported()).build();

    assertThat(VideoCopyBandwidth.averageBandwidth(source, AudioDecision.none(), NINE_SECONDS))
        .isEqualTo(892_217L);
    assertThat(VideoCopyBandwidth.peakBandwidth(source, AudioDecision.none(), NINE_SECONDS))
        .isEmpty();
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
    assertThatNullPointerException()
        .isThrownBy(() -> VideoCopyBandwidth.peakBandwidth(null, audio, playlist))
        .withMessageContaining("source");
    assertThatNullPointerException()
        .isThrownBy(() -> VideoCopyBandwidth.peakBandwidth(source, null, playlist))
        .withMessageContaining("audio");
    assertThatNullPointerException()
        .isThrownBy(() -> VideoCopyBandwidth.peakBandwidth(source, audio, null))
        .withMessageContaining("playlist");
    assertThatNullPointerException()
        .isThrownBy(() -> VideoCopyBandwidth.estimateExceedsSegmentCap(null, audio, playlist))
        .withMessageContaining("source");
    assertThatNullPointerException()
        .isThrownBy(() -> VideoCopyBandwidth.estimateExceedsSegmentCap(source, null, playlist))
        .withMessageContaining("audio");
    assertThatNullPointerException()
        .isThrownBy(() -> VideoCopyBandwidth.estimateExceedsSegmentCap(source, audio, null))
        .withMessageContaining("playlist");
  }

  @Test
  @DisplayName(
      "Should refuse to count a copied audio track when the source or the timeline is missing")
  void shouldRefuseToCountACopiedAudioTrackWhenTheSourceOrTheTimelineIsMissing() {
    var source = defaultProbeBuilder().build();
    var timeline = new MediaSegmentTimeline.PlainGrid(Duration.ofSeconds(9), Duration.ofSeconds(6));

    assertThatNullPointerException()
        .isThrownBy(() -> VideoCopyBandwidth.copiedAudioBitrate(null, timeline))
        .withMessageContaining("source");
    assertThatNullPointerException()
        .isThrownBy(() -> VideoCopyBandwidth.copiedAudioBitrate(source, null))
        .withMessageContaining("timeline");
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
    streams.add(
        measuredStream(videoIndex).measuredBytes(videoBytes).packetCount(VIDEO_PACKETS).build());
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

  /** A stream of no bytes in one keyframe interval, before the caller sets its counts. */
  private static MeasuredStream.MeasuredStreamBuilder measuredStream(int streamIndex) {
    return MeasuredStream.builder()
        .streamIndex(streamIndex)
        .timeBaseNumerator(1)
        .timeBaseDenominator(1_000)
        .measuredDuration(1_000)
        .keyframeIntervalBytes(KeyframeIntervalCounts.of(0))
        .keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(0));
  }

  private static StreamMeasurements measurementOf(List<MeasuredStream> streams) {
    return new StreamMeasurements(StreamMeasurementSource.SAMPLE_TABLE, streams);
  }
}
