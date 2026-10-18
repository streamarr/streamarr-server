package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The interim copy rule of ADR 0013 and ADR 0036, which holds until streamarr/streamarr-server#396.
 * The server copies the video only when every condition holds: the compatible base is SDR or
 * unspecified; no Dolby Vision compatibility identifier disagrees with the signaled transfer; the
 * source is not H.264, or is 8-bit 4:2:0 H.264; and the client names the source's codec. A copy
 * also needs the codec string that the probe stored, under a sample entry that a playlist can
 * describe: never hev1 or a Dolby Vision entry (ADR 0013).
 */
@Tag("UnitTest")
@DisplayName("Transcode Decision Copy Rule Tests")
class TranscodeDecisionCopyRuleTest {

  private static final Set<String> EVERY_VIDEO_CODEC_FAMILY = Set.of("h264", "hevc", "av1");

  // The Dolby Vision configuration record's bl_signal_compatibility_id names the base format that
  // the stream claims: 0 none, 1 HDR10, 2 SDR, 4 HLG, and 6 the PQ base of profile 7.
  private static final Map<Integer, CompatibleBase> CLAIMED_BASE_BY_COMPATIBILITY_ID =
      Map.of(
          0, CompatibleBase.NONE,
          1, CompatibleBase.PQ,
          2, CompatibleBase.SDR,
          4, CompatibleBase.HLG,
          6, CompatibleBase.PQ);

  private final TranscodeDecisionService service =
      new TranscodeDecisionService(
          StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build(),
          new SimpleMeterRegistry());

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyCombination")
  @DisplayName("Should copy the video only when every condition of the interim copy rule holds")
  void shouldCopyTheVideoOnlyWhenEveryConditionOfTheInterimCopyRuleHolds(CopyCase copyCase) {
    var decision =
        service.decide(
            copyCase.source().probe(), options(copyCase.clientCodecs()), EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode().copiesVideo()).isEqualTo(copyCase.copyPermitted());
  }

  static Stream<CopyCase> everyCombination() {
    var cases = new ArrayList<CopyCase>();
    for (var sampleEntry : SampleEntry.values()) {
      for (var base : CompatibleBase.values()) {
        for (var dolbyVision : dolbyVisionClaims()) {
          for (var bitDepth : List.of(OptionalInt.of(8), OptionalInt.of(10), OptionalInt.empty())) {
            for (var chroma : chromaSubsamplings()) {
              for (var clientCodecs : clientCodecLists()) {
                cases.add(
                    CopyCase.builder()
                        .source(
                            Source.builder()
                                .sampleEntry(sampleEntry)
                                .dynamicRange(
                                    new VideoDynamicRange(base, Presence.UNSPECIFIED, dolbyVision))
                                .bitDepth(bitDepth)
                                .chromaSubsampling(chroma)
                                .build())
                        .clientCodecs(clientCodecs)
                        .build());
              }
            }
          }
        }
      }
    }

    return cases.stream();
  }

  private static List<DolbyVision> dolbyVisionClaims() {
    var claims = new ArrayList<DolbyVision>();
    claims.add(new DolbyVision.Unspecified());
    claims.add(new DolbyVision.Present(Optional.empty(), Presence.UNSPECIFIED));
    claims.add(dolbyVisionRecord(OptionalInt.empty()));
    for (var compatibilityId : List.of(0, 1, 2, 3, 4, 6)) {
      claims.add(dolbyVisionRecord(OptionalInt.of(compatibilityId)));
    }

    return claims;
  }

  private static List<Optional<ChromaSubsampling>> chromaSubsamplings() {
    return List.of(
        Optional.of(ChromaSubsampling.CHROMA_420),
        Optional.of(ChromaSubsampling.CHROMA_422),
        Optional.empty());
  }

  // The codec names that the web client, the Apple TV app and an AV1 client send today.
  private static List<List<String>> clientCodecLists() {
    return List.of(List.of("h264"), List.of("h264", "hevc"), List.of("av1", "h264"));
  }

  @Test
  @DisplayName("Should copy a Dolby Vision 8.2 source under hvc1 when the client names HEVC")
  void shouldCopyADolbyVision82SourceUnderHvc1WhenTheClientNamesHevc() {
    var source =
        Source.builder()
            .sampleEntry(SampleEntry.HVC1)
            .dynamicRange(
                new VideoDynamicRange(
                    CompatibleBase.SDR, Presence.UNSPECIFIED, dolbyVisionRecord(OptionalInt.of(2))))
            .bitDepth(OptionalInt.of(10))
            .build();

    var decision =
        service.decide(source.probe(), options(List.of("h264", "hevc")), EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoSource().flatMap(VideoPlaybackFacts::codecString))
        .contains("hvc1.2.4.L120.90");
  }

  @Test
  @DisplayName("Should encode an HDR10 HEVC source to H.264 when the Apple TV app names HEVC")
  void shouldEncodeAnHdr10HevcSourceToH264WhenTheAppleTvAppNamesHevc() {
    var source =
        Source.builder()
            .sampleEntry(SampleEntry.HVC1)
            .dynamicRange(
                new VideoDynamicRange(
                    CompatibleBase.PQ, Presence.UNSPECIFIED, new DolbyVision.Unspecified()))
            .bitDepth(OptionalInt.of(10))
            .build();

    var decision =
        service.decide(source.probe(), options(List.of("h264", "hevc")), EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
  }

  @Test
  @DisplayName("Should encode a Dolby Vision 7 source when it is tagged as SDR")
  void shouldEncodeADolbyVision7SourceWhenItIsTaggedAsSdr() {
    var source =
        Source.builder()
            .sampleEntry(SampleEntry.HVC1)
            .dynamicRange(
                new VideoDynamicRange(
                    CompatibleBase.SDR, Presence.UNSPECIFIED, dolbyVisionRecord(OptionalInt.of(6))))
            .bitDepth(OptionalInt.of(10))
            .build();

    var decision =
        service.decide(source.probe(), options(List.of("h264", "hevc")), EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  @Test
  @DisplayName("Should encode a 10-bit H.264 source when every client names H.264")
  void shouldEncodeA10BitH264SourceWhenEveryClientNamesH264() {
    var source =
        Source.builder().sampleEntry(SampleEntry.AVC1).bitDepth(OptionalInt.of(10)).build();

    var decision =
        service.decide(source.probe(), options(List.of("h264")), EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  @Test
  @DisplayName("Should describe the chosen video stream when the decision copies its video")
  void shouldDescribeTheChosenVideoStreamWhenTheDecisionCopiesItsVideo() {
    var range =
        new VideoDynamicRange(
            CompatibleBase.SDR, Presence.UNSPECIFIED, new DolbyVision.Unspecified());
    var source = Source.builder().sampleEntry(SampleEntry.AVC1).dynamicRange(range).build();

    var decision =
        service.decide(source.probe(), options(List.of("h264")), EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoSource()).isEqualTo(source.probe().videoPlaybackFacts());
  }

  @Test
  @DisplayName("Should describe the chosen video stream when the decision encodes its video")
  void shouldDescribeTheChosenVideoStreamWhenTheDecisionEncodesItsVideo() {
    var range =
        new VideoDynamicRange(
            CompatibleBase.HLG, Presence.UNSPECIFIED, new DolbyVision.Unspecified());
    var source = Source.builder().sampleEntry(SampleEntry.HVC1).dynamicRange(range).build();

    var decision =
        service.decide(source.probe(), options(List.of("h264")), EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoSource()).isEqualTo(source.probe().videoPlaybackFacts());
  }

  @Test
  @DisplayName("Should encode the video when the probe reported no playback facts")
  void shouldEncodeTheVideoWhenTheProbeReportedNoPlaybackFacts() {
    var probe =
        Source.builder()
            .sampleEntry(SampleEntry.AVC1)
            .build()
            .probeBuilder()
            .videoPlaybackFacts(Optional.empty())
            .build();

    var decision = service.decide(probe, options(List.of("h264")), EVERY_VIDEO_CODEC_FAMILY);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoSource()).isEmpty();
  }

  private static DolbyVision dolbyVisionRecord(OptionalInt compatibilityId) {
    return new DolbyVision.Present(
        Optional.of(
            DolbyVisionConfiguration.builder()
                .profile(OptionalInt.of(8))
                .baseLayerCompatibilityId(compatibilityId)
                .baseLayer(true)
                .rpu(true)
                .build()),
        Presence.UNSPECIFIED);
  }

  private static StreamingOptions options(List<String> supportedCodecs) {
    return StreamingOptions.builder()
        .supportedCodecs(supportedCodecs)
        .supportedAudioCodecs(StreamingOptions.DEFAULT_SUPPORTED_AUDIO_CODECS)
        .maxAudioChannels(StreamingOptions.DEFAULT_MAX_AUDIO_CHANNELS)
        .build();
  }

  /**
   * The sample entry that a copy of the source would carry, which the probe names at the start of
   * the stored codec string. NONE stands for a source whose probe stored no codec string.
   */
  enum SampleEntry {
    AVC1("h264", Optional.of("avc1.640028")),
    HVC1("hevc", Optional.of("hvc1.2.4.L120.90")),
    HEV1("hevc", Optional.of("hev1.2.4.L120.90")),
    DVH1("hevc", Optional.of("dvh1.08.06")),
    AV01("av1", Optional.of("av01.0.08M.08")),
    NONE("hevc", Optional.empty());

    private final String codec;
    private final Optional<String> codecString;

    SampleEntry(String codec, Optional<String> codecString) {
      this.codec = codec;
      this.codecString = codecString;
    }

    boolean isDescribableInAPlaylist() {
      return this == AVC1 || this == HVC1 || this == AV01;
    }
  }

  @Builder
  record Source(
      @NonNull SampleEntry sampleEntry,
      VideoDynamicRange dynamicRange,
      OptionalInt bitDepth,
      Optional<ChromaSubsampling> chromaSubsampling) {

    Source {
      dynamicRange =
          dynamicRange == null
              ? new VideoDynamicRange(
                  CompatibleBase.SDR, Presence.UNSPECIFIED, new DolbyVision.Unspecified())
              : dynamicRange;
      bitDepth = bitDepth == null ? OptionalInt.of(8) : bitDepth;
      chromaSubsampling =
          chromaSubsampling == null ? Optional.of(ChromaSubsampling.CHROMA_420) : chromaSubsampling;
    }

    // The chosen video stream is stream 1, after cover art. Keyframes open every 6 s grid
    // interval, so the keyframes never rule out a copy.
    MediaProbe probe() {
      return probeBuilder().build();
    }

    MediaProbe.MediaProbeBuilder probeBuilder() {
      return MediaProbe.builder()
          .duration(Duration.ofMinutes(10))
          .framerate(OptionalDouble.of(24))
          .width(1920)
          .height(1080)
          .videoCodec(sampleEntry.codec)
          .audioCodec("aac")
          .streams(List.of(ProbeFixture.audioStreamBuilder("aac").index(2).build()))
          .bitrate(5_000_000)
          .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(2), Duration.ofMinutes(10)))
          .videoPlaybackFacts(
              Optional.of(
                  VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                      .streamIndex(1)
                      .dynamicRange(dynamicRange)
                      .bitDepth(bitDepth)
                      .chromaSubsampling(chromaSubsampling)
                      .codecString(sampleEntry.codecString)
                      .build()));
    }

    boolean isEightBitFourTwoZero() {
      return bitDepth.equals(OptionalInt.of(8))
          && chromaSubsampling.equals(Optional.of(ChromaSubsampling.CHROMA_420));
    }

    boolean hasDisagreeingDolbyVisionCompatibility() {
      if (!(dynamicRange.dolbyVision() instanceof DolbyVision.Present(var declared, _))
          || declared.isEmpty()
          || declared.get().baseLayerCompatibilityId().isEmpty()) {
        return false;
      }

      var claimed =
          CLAIMED_BASE_BY_COMPATIBILITY_ID.get(
              declared.get().baseLayerCompatibilityId().getAsInt());
      return claimed != dynamicRange.compatibleBase();
    }
  }

  @Builder
  record CopyCase(@NonNull Source source, @NonNull List<String> clientCodecs) {

    boolean copyPermitted() {
      var base = source.dynamicRange().compatibleBase();
      return (base == CompatibleBase.SDR || base == CompatibleBase.UNSPECIFIED)
          && !source.hasDisagreeingDolbyVisionCompatibility()
          && (!"h264".equals(source.sampleEntry().codec) || source.isEightBitFourTwoZero())
          && clientCodecs.contains(source.sampleEntry().codec)
          && source.sampleEntry().isDescribableInAPlaylist();
    }

    @Override
    public String toString() {
      return String.join(
          " ",
          source.sampleEntry().name(),
          source.dynamicRange().compatibleBase().name(),
          source.dynamicRange().dolbyVision().toString(),
          source.bitDepth().toString(),
          source.chromaSubsampling().toString(),
          clientCodecs.toString());
    }
  }
}
