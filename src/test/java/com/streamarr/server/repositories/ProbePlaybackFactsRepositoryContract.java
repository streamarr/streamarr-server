package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ChapterKnowledge;
import com.streamarr.server.domain.streaming.ChosenVideoStream;
import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.ChromaticityCoordinates;
import com.streamarr.server.domain.streaming.ColorPrimaries;
import com.streamarr.server.domain.streaming.ColorRange;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.ContainerCropping;
import com.streamarr.server.domain.streaming.ContentLightLevel;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.DolbyVisionRpuHeader;
import com.streamarr.server.domain.streaming.DolbyVisionRpuPlacement;
import com.streamarr.server.domain.streaming.FieldOrder;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.FrameRatePacketScan;
import com.streamarr.server.domain.streaming.FrameRateRule;
import com.streamarr.server.domain.streaming.FrameRateRuleInputs;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MasteringDisplayColorVolume;
import com.streamarr.server.domain.streaming.MatrixCoefficients;
import com.streamarr.server.domain.streaming.MatroskaStatistics;
import com.streamarr.server.domain.streaming.OutputFrameRateBasis;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.TransferCharacteristics;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * How every {@link MediaFileContainerInfoRepository} stores the facts that probe versions 4 and 5
 * add for playback (ADR 0032, ADR 0036): a stored outcome reads back each fact that its probe
 * reported, and keeps an unknown value apart from a known one.
 */
public interface ProbePlaybackFactsRepositoryContract {

  SourceFileSnapshot PLAYBACK_FACTS_SNAPSHOT =
      new SourceFileSnapshot(4321, Instant.parse("2026-10-16T10:00:00Z"));

  MediaFileContainerInfoRepository repository();

  UUID createMediaFile();

  @Test
  @DisplayName("Should read every playback fact back when a version 4 outcome is stored")
  default void shouldReadEveryPlaybackFactBackWhenAVersion4OutcomeIsStored() {
    var mediaFileId = createMediaFile();
    var published =
        outcomeBuilder()
            .streams(List.of(coverArt(), hdrPhoneRecording(), atmosAudio()))
            .chosenVideoStream(Optional.of(new ChosenVideoStream(1, scannedSixtyFramesPerSecond())))
            .build();

    repository().publish(publication(mediaFileId, published));

    var stored = storedOutcome(mediaFileId);
    assertThat(stored.streams()).isEqualTo(published.streams());
    assertThat(stored.chosenVideoStream()).isEqualTo(published.chosenVideoStream());
  }

  @Test
  @DisplayName("Should read every frame fact back when a version 5 outcome is stored")
  default void shouldReadEveryFrameFactBackWhenAVersion5OutcomeIsStored() {
    var mediaFileId = createMediaFile();
    var profile5WithoutRecord =
        new VideoDynamicRange(
            CompatibleBase.NONE,
            Presence.ABSENT,
            new DolbyVision.Present(
                Optional.empty(),
                Presence.PRESENT,
                Optional.of(
                    DolbyVisionRpuHeader.builder()
                        .vdrRpuProfile(0)
                        .baseLayerFullRange(true)
                        .enhancementLayerSpatialResamplingFilter(false)
                        .disableResidual(true)
                        .vdrBitDepth(12)
                        .build()),
                DolbyVisionRpuPlacement.AFTER_OWN_PICTURE));
    var chosen =
        video()
            .index(1)
            .codec(Optional.of("hevc"))
            .dynamicRange(Optional.of(profile5WithoutRecord))
            .videoProperties(
                Optional.of(
                    VideoStreamProperties.builder()
                        .codecProfile(Optional.of(VideoCodecProfile.HEVC_MAIN_10))
                        .codecLevel(OptionalInt.of(51))
                        .codecTier(Optional.of(VideoCodecTier.HIGH))
                        .colorPrimaries(Optional.of(ColorPrimaries.BT709))
                        .transferCharacteristics(Optional.of(TransferCharacteristics.BT709))
                        .matrixCoefficients(Optional.of(MatrixCoefficients.BT709))
                        .colorRange(Optional.of(ColorRange.FULL))
                        .firstFramesRead(true)
                        .masteringDisplayOmitted(true)
                        .contentLightLevelOmitted(true)
                        .build()))
            .build();
    // A Dolby Vision stream other than the chosen one: the probe reads none of its frames, so it
    // names no property, no RPU header and no RPU placement.
    var unnamed =
        video()
            .index(3)
            .dynamicRange(
                Optional.of(
                    new VideoDynamicRange(
                        CompatibleBase.NONE,
                        Presence.UNSPECIFIED,
                        new DolbyVision.Present(Optional.empty(), Presence.UNSPECIFIED))))
            .videoProperties(Optional.of(VideoStreamProperties.builder().build()))
            .build();
    var published =
        outcomeBuilder()
            .streams(List.of(coverArt(), chosen, atmosAudio(), unnamed))
            .chosenVideoStream(Optional.of(new ChosenVideoStream(1, declaredFilmRate())))
            .build();

    repository()
        .publish(
            ProbePublication.builder()
                .mediaFileId(mediaFileId)
                .snapshot(PLAYBACK_FACTS_SNAPSHOT)
                .probeVersion(ProbeVersion.FIRST_TO_REPORT_FRAME_FACTS)
                .outcome(published)
                .build());

    assertThat(storedOutcome(mediaFileId).streams()).isEqualTo(published.streams());
  }

  @ParameterizedTest
  @EnumSource(DolbyVisionRpuPlacement.class)
  @DisplayName("Should read each RPU placement back when a version 5 outcome stores it")
  default void shouldReadEachRpuPlacementBackWhenAVersion5OutcomeStoresIt(
      DolbyVisionRpuPlacement placement) {
    var mediaFileId = createMediaFile();
    var chosen =
        video()
            .codec(Optional.of("hevc"))
            .dynamicRange(
                Optional.of(
                    new VideoDynamicRange(
                        CompatibleBase.NONE,
                        Presence.ABSENT,
                        new DolbyVision.Present(
                            Optional.empty(), Presence.PRESENT, Optional.empty(), placement))))
            .videoProperties(
                Optional.of(VideoStreamProperties.builder().firstFramesRead(true).build()))
            .build();
    var published =
        outcomeBuilder()
            .streams(List.of(chosen))
            .chosenVideoStream(Optional.of(new ChosenVideoStream(0, declaredFilmRate())))
            .build();

    repository()
        .publish(
            ProbePublication.builder()
                .mediaFileId(mediaFileId)
                .snapshot(PLAYBACK_FACTS_SNAPSHOT)
                .probeVersion(ProbeVersion.FIRST_TO_REPORT_FRAME_FACTS)
                .outcome(published)
                .build());

    assertThat(storedOutcome(mediaFileId).streams()).isEqualTo(published.streams());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName(
      "Should read whether the first packets carry presentation times back when a version 5"
          + " outcome stores it")
  default void
      shouldReadWhetherTheFirstPacketsCarryPresentationTimesBackWhenAVersion5OutcomeStoresIt(
          boolean carried) {
    var mediaFileId = createMediaFile();
    var chosen =
        video()
            .codec(Optional.of("hevc"))
            .videoProperties(
                Optional.of(
                    VideoStreamProperties.builder()
                        .firstFramesRead(true)
                        .firstPacketsCarryPresentationTimes(Optional.of(carried))
                        .build()))
            .build();
    var published =
        outcomeBuilder()
            .streams(List.of(chosen))
            .chosenVideoStream(Optional.of(new ChosenVideoStream(0, declaredFilmRate())))
            .build();

    repository()
        .publish(
            ProbePublication.builder()
                .mediaFileId(mediaFileId)
                .snapshot(PLAYBACK_FACTS_SNAPSHOT)
                .probeVersion(ProbeVersion.FIRST_TO_REPORT_FRAME_FACTS)
                .outcome(published)
                .build());

    assertThat(storedOutcome(mediaFileId).streams()).isEqualTo(published.streams());
  }

  @ParameterizedTest
  @MethodSource("titleOmissionStates")
  @DisplayName("Should retain every optional metadata fact when a probe outcome is published")
  default void shouldRetainEveryOptionalMetadataFactWhenAProbeOutcomeIsPublished(
      Optional<Boolean> titlesOmitted) {
    var mediaFileId = createMediaFile();
    var display =
        MasteringDisplayColorVolume.builder()
            .red(new ChromaticityCoordinates(50000, 0))
            .green(new ChromaticityCoordinates(8500, 39850))
            .blue(new ChromaticityCoordinates(6550, 2300))
            .whitePoint(new ChromaticityCoordinates(15635, 16450))
            .maxLuminance(4_294_967_295L)
            .minLuminance(0)
            .build();
    var audio =
        StreamInfo.builder()
            .index(1)
            .codecType("audio")
            .endTime(Optional.of(Duration.ofNanos(-1)))
            .hearingImpaired(Optional.of(false));
    var subtitle =
        StreamInfo.builder().index(2).codecType("subtitle").hearingImpaired(Optional.of(true));
    if (titlesOmitted.equals(Optional.of(false))) {
      audio.title(Optional.of("English SDH")).handlerName(Optional.of(""));
      subtitle.title(Optional.of("Français")).handlerName(Optional.of("SubtitleHandler"));
    }

    var published =
        outcomeBuilder()
            .streams(
                List.of(
                    video()
                        .endTime(Optional.of(Duration.ofSeconds(250, 123)))
                        .masteringDisplay(Optional.of(display))
                        .contentLightLevel(Optional.of(new ContentLightLevel(0, 65535)))
                        .build(),
                    audio.build(),
                    subtitle.build()))
            .streamTitlesOmitted(titlesOmitted)
            .build();

    repository().publish(publication(mediaFileId, published));

    assertThat(storedOutcome(mediaFileId)).isEqualTo(published);

    var absent = outcomeBuilder().streams(List.of(video().build())).build();
    repository().publish(publication(mediaFileId, absent));

    assertThat(storedOutcome(mediaFileId)).isEqualTo(absent);
  }

  static Stream<Optional<Boolean>> titleOmissionStates() {
    return Stream.of(Optional.empty(), Optional.of(false), Optional.of(true));
  }

  @ParameterizedTest
  @MethodSource("dynamicRanges")
  @DisplayName("Should keep each dynamic range apart when an outcome stores it")
  default void shouldKeepEachDynamicRangeApartWhenAnOutcomeStoresIt(VideoDynamicRange range) {
    var mediaFileId = createMediaFile();
    var published =
        outcomeBuilder()
            .streams(List.of(video().dynamicRange(Optional.of(range)).build()))
            .chosenVideoStream(Optional.of(new ChosenVideoStream(0, declaredFilmRate())))
            .build();

    repository().publish(publication(mediaFileId, published));

    assertThat(storedOutcome(mediaFileId).streams().getFirst().dynamicRange()).contains(range);
  }

  static Stream<VideoDynamicRange> dynamicRanges() {
    var noClaims =
        DolbyVisionConfiguration.builder().baseLayer(true).enhancementLayer(false).rpu(true);
    return Stream.of(
        VideoDynamicRange.UNSPECIFIED,
        new VideoDynamicRange(CompatibleBase.SDR, Presence.ABSENT, new DolbyVision.Absent()),
        new VideoDynamicRange(
            CompatibleBase.SDR,
            Presence.UNSPECIFIED,
            new DolbyVision.Present(Optional.empty(), Presence.UNSPECIFIED)),
        // Dolby Vision profile 5 declares compatibility identifier 0, which differs from none.
        new VideoDynamicRange(
            CompatibleBase.NONE,
            Presence.UNSPECIFIED,
            new DolbyVision.Present(
                Optional.of(
                    noClaims
                        .profile(OptionalInt.of(5))
                        .level(OptionalInt.of(6))
                        .baseLayerCompatibilityId(OptionalInt.of(0))
                        .build()),
                Presence.UNSPECIFIED)),
        new VideoDynamicRange(
            CompatibleBase.HLG,
            Presence.PRESENT,
            new DolbyVision.Present(Optional.of(noClaims.build()), Presence.ABSENT)));
  }

  @Test
  @DisplayName("Should read no output frame rate back when the frame-rate rule refused one")
  default void shouldReadNoOutputFrameRateBackWhenTheFrameRateRuleRefusedOne() {
    var mediaFileId = createMediaFile();
    var refused =
        FrameRateRule.builder()
            .outputFrameRate(Optional.empty())
            .basis(OutputFrameRateBasis.REFUSED)
            .inputs(
                FrameRateRuleInputs.builder()
                    .packetScan(
                        Optional.of(FrameRatePacketScan.builder().scannedFrameCount(1).build()))
                    .build())
            .build();
    var published =
        outcomeBuilder()
            .streams(List.of(video().build()))
            .chosenVideoStream(Optional.of(new ChosenVideoStream(0, refused)))
            .build();

    repository().publish(publication(mediaFileId, published));

    assertThat(storedOutcome(mediaFileId).chosenVideoStream())
        .contains(new ChosenVideoStream(0, refused));
  }

  @ParameterizedTest
  @MethodSource("partialMatroskaStatistics")
  @DisplayName("Should read Matroska statistics back when the stream carries only one of its tags")
  default void shouldReadMatroskaStatisticsBackWhenTheStreamCarriesOnlyOneOfItsTags(
      MatroskaStatistics statistics) {
    var mediaFileId = createMediaFile();
    var rule =
        FrameRateRule.builder()
            .outputFrameRate(Optional.of(new Fraction(25, 1)))
            .basis(OutputFrameRateBasis.MATROSKA_STATISTICS)
            .inputs(
                FrameRateRuleInputs.builder().matroskaStatistics(Optional.of(statistics)).build())
            .build();
    var published =
        outcomeBuilder()
            .streams(List.of(video().build()))
            .chosenVideoStream(Optional.of(new ChosenVideoStream(0, rule)))
            .build();

    repository().publish(publication(mediaFileId, published));

    assertThat(storedOutcome(mediaFileId).chosenVideoStream())
        .contains(new ChosenVideoStream(0, rule));
  }

  static Stream<MatroskaStatistics> partialMatroskaStatistics() {
    return Stream.of(
        new MatroskaStatistics(OptionalLong.of(3_000), Optional.empty()),
        new MatroskaStatistics(OptionalLong.empty(), Optional.of(Duration.ofSeconds(120))));
  }

  @Test
  @DisplayName(
      "Should read no playback facts back when an earlier probe version stored the outcome")
  default void shouldReadNoPlaybackFactsBackWhenAnEarlierProbeVersionStoredTheOutcome() {
    var mediaFileId = createMediaFile();
    var published = outcomeBuilder().streams(List.of(video().build())).build();

    repository()
        .publish(
            ProbePublication.builder()
                .mediaFileId(mediaFileId)
                .snapshot(PLAYBACK_FACTS_SNAPSHOT)
                .probeVersion(ProbeVersion.FIRST_TO_REPORT_PLAYBACK_FACTS - 1)
                .outcome(published)
                .build());

    var stored = storedOutcome(mediaFileId);
    assertThat(stored.chosenVideoStream()).isEmpty();
    assertThat(stored.streams()).containsExactly(video().build());
  }

  private ProbeOutcome.Success storedOutcome(UUID mediaFileId) {
    var outcome =
        repository()
            .findByMediaFileId(mediaFileId)
            .orElseThrow()
            .getOutcome(KeyframeKnowledge.NotReported::new, ChapterKnowledge.NotReported::new);
    assertThat(outcome).isInstanceOf(ProbeOutcome.Success.class);
    return (ProbeOutcome.Success) outcome;
  }

  private static ProbePublication publication(UUID mediaFileId, ProbeOutcome outcome) {
    return ProbePublication.builder()
        .mediaFileId(mediaFileId)
        .snapshot(PLAYBACK_FACTS_SNAPSHOT)
        .probeVersion(ProbeVersion.FIRST_TO_REPORT_PLAYBACK_FACTS)
        .outcome(outcome)
        .build();
  }

  private static ProbeOutcome.Success.SuccessBuilder outcomeBuilder() {
    return ProbeOutcome.Success.builder()
        .container(ProbeContainer.builder().format(Optional.of("mov,mp4,m4a,3gp,3g2,mj2")).build())
        .keyframes(new KeyframeKnowledge.NotReported());
  }

  private static StreamInfo.StreamInfoBuilder video() {
    return StreamInfo.builder()
        .index(0)
        .codecType("video")
        .codec(Optional.of("h264"))
        .width(OptionalInt.of(1920))
        .height(OptionalInt.of(1080));
  }

  // Embedded cover art: an attached picture, which the probe never chooses.
  private static StreamInfo coverArt() {
    return StreamInfo.builder()
        .index(0)
        .codecType("video")
        .codec(Optional.of("mjpeg"))
        .width(OptionalInt.of(600))
        .height(OptionalInt.of(600))
        .dynamicRange(Optional.of(VideoDynamicRange.UNSPECIFIED))
        .bitDepth(OptionalInt.of(8))
        .chromaSubsampling(Optional.of(ChromaSubsampling.CHROMA_444))
        .build();
  }

  // An upright HLG phone recording with Dolby Vision 8.4 and a container crop.
  private static StreamInfo hdrPhoneRecording() {
    return StreamInfo.builder()
        .index(1)
        .codecType("video")
        .codec(Optional.of("hevc"))
        .width(OptionalInt.of(1920))
        .height(OptionalInt.of(1080))
        .framerate(OptionalDouble.of(59.94))
        .dynamicRange(
            Optional.of(
                new VideoDynamicRange(
                    CompatibleBase.HLG,
                    Presence.UNSPECIFIED,
                    new DolbyVision.Present(
                        Optional.of(
                            DolbyVisionConfiguration.builder()
                                .profile(OptionalInt.of(8))
                                .level(OptionalInt.of(7))
                                .baseLayerCompatibilityId(OptionalInt.of(4))
                                .baseLayer(true)
                                .enhancementLayer(false)
                                .rpu(true)
                                .build()),
                        Presence.UNSPECIFIED))))
        .bitDepth(OptionalInt.of(10))
        .chromaSubsampling(Optional.of(ChromaSubsampling.CHROMA_420))
        .rotationDegrees(OptionalInt.of(-90))
        .containerCropping(
            Optional.of(ContainerCropping.builder().top(0).bottom(8).left(2).right(4).build()))
        .codecString(Optional.of("hvc1.2.4.L123.B0"))
        .build();
  }

  private static StreamInfo atmosAudio() {
    return StreamInfo.builder()
        .index(2)
        .codecType("audio")
        .codec(Optional.of("eac3"))
        .channels(OptionalInt.of(6))
        .codecString(Optional.of("ec-3"))
        .sampleRate(OptionalInt.of(48_000))
        .audioProfile(Optional.of("Dolby Digital Plus + Dolby Atmos"))
        .build();
  }

  private static FrameRateRule scannedSixtyFramesPerSecond() {
    return FrameRateRule.builder()
        .outputFrameRate(Optional.of(new Fraction(60, 1)))
        .basis(OutputFrameRateBasis.PACKET_SCAN)
        .inputs(
            FrameRateRuleInputs.builder()
                .baseRate(Optional.of(new Fraction(120, 1)))
                .averageRate(Optional.of(new Fraction(59_956, 1_000)))
                .fieldOrder(Optional.of(FieldOrder.PROGRESSIVE))
                .timeBase(Optional.of(new Fraction(1, 600)))
                .matroskaStatistics(
                    Optional.of(
                        new MatroskaStatistics(
                            OptionalLong.of(7_195),
                            Optional.of(Duration.ofSeconds(120, 5_000_001)))))
                .packetScan(
                    Optional.of(
                        FrameRatePacketScan.builder()
                            .motionRate(OptionalDouble.of(59.97))
                            .scannedFrameCount(1_799)
                            .scannedDuration(Optional.of(Duration.ofSeconds(30, 16_667)))
                            .build()))
                .build())
        .build();
  }

  private static FrameRateRule declaredFilmRate() {
    return FrameRateRule.builder()
        .outputFrameRate(Optional.of(new Fraction(24_000, 1_001)))
        .basis(OutputFrameRateBasis.DECLARED_RATES)
        .inputs(
            FrameRateRuleInputs.builder()
                .baseRate(Optional.of(new Fraction(24_000, 1_001)))
                .averageRate(Optional.of(new Fraction(24_000, 1_001)))
                .build())
        .build();
  }
}
