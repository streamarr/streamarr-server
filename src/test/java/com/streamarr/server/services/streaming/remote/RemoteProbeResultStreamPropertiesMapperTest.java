package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.ColorPrimaries;
import com.streamarr.server.domain.streaming.ColorRange;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionRpuHeader;
import com.streamarr.server.domain.streaming.DolbyVisionRpuPlacement;
import com.streamarr.server.domain.streaming.MatrixCoefficients;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.TransferCharacteristics;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.server.fixtures.WorkerProbeResultFixture;
import com.streamarr.transcode.v1.CompatibleBase;
import com.streamarr.transcode.v1.ContentLightLevel;
import com.streamarr.transcode.v1.DolbyVisionAbsent;
import com.streamarr.transcode.v1.DolbyVisionConfiguration;
import com.streamarr.transcode.v1.DolbyVisionPresent;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Remote Probe Result Stream Properties Mapper Tests")
class RemoteProbeResultStreamPropertiesMapperTest {

  private static final int PROBE_VERSION_4 = 4;
  private static final int PROBE_VERSION_5 = 5;

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static final com.streamarr.transcode.v1.TransferCharacteristics PQ_TRANSFER =
      com.streamarr.transcode.v1.TransferCharacteristics.TRANSFER_CHARACTERISTICS_SMPTE2084;

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static final com.streamarr.transcode.v1.TransferCharacteristics SDR_TRANSFER =
      com.streamarr.transcode.v1.TransferCharacteristics.TRANSFER_CHARACTERISTICS_BT709;

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static final com.streamarr.transcode.v1.Presence REPORTED_PRESENT =
      com.streamarr.transcode.v1.Presence.PRESENCE_PRESENT;

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static final com.streamarr.transcode.v1.Presence REPORTED_ABSENT =
      com.streamarr.transcode.v1.Presence.PRESENCE_ABSENT;

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static final com.streamarr.transcode.v1.DolbyVisionRpuPlacement REPORTED_STARTS_SAMPLE =
      com.streamarr.transcode.v1.DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE;

  private final RemoteProbeResultMapper mapper = new RemoteProbeResultMapper();

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should keep each video property when a probe version 5 result reports them")
  void shouldKeepEachVideoPropertyWhenAProbeVersion5ResultReportsThem() {
    var hdr10 =
        hevc()
            .setCodecProfile(
                com.streamarr.transcode.v1.VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN_10)
            .setCodecLevel(51)
            .setCodecTier(com.streamarr.transcode.v1.VideoCodecTier.VIDEO_CODEC_TIER_HIGH)
            .setColorPrimaries(com.streamarr.transcode.v1.ColorPrimaries.COLOR_PRIMARIES_BT2020)
            .setTransferCharacteristics(
                com.streamarr.transcode.v1.TransferCharacteristics
                    .TRANSFER_CHARACTERISTICS_SMPTE2084)
            .setMatrixCoefficients(
                com.streamarr.transcode.v1.MatrixCoefficients.MATRIX_COEFFICIENTS_BT2020_NCL)
            .setColorRange(com.streamarr.transcode.v1.ColorRange.COLOR_RANGE_LIMITED)
            .setFirstFramesRead(true)
            .setMasteringDisplayOmitted(true)
            .setContentLightLevelOmitted(true);
    hdr10.getDynamicRangeBuilder().setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ);

    var stream = videoOf(version5(media -> media.setStreams(0, hdr10)));

    assertThat(stream.videoProperties())
        .contains(
            VideoStreamProperties.builder()
                .codecProfile(Optional.of(VideoCodecProfile.HEVC_MAIN_10))
                .codecLevel(OptionalInt.of(51))
                .codecTier(Optional.of(VideoCodecTier.HIGH))
                .colorPrimaries(Optional.of(ColorPrimaries.BT2020))
                .transferCharacteristics(Optional.of(TransferCharacteristics.SMPTE2084))
                .matrixCoefficients(Optional.of(MatrixCoefficients.BT2020_NCL))
                .colorRange(Optional.of(ColorRange.LIMITED))
                .firstFramesRead(true)
                .masteringDisplayOmitted(true)
                .contentLightLevelOmitted(true)
                .build());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should report no named property when a probe version 5 stream names none")
  void shouldReportNoNamedPropertyWhenAProbeVersion5StreamNamesNone() {
    var stream = videoOf(version5(media -> media.setStreams(0, hevc())));

    assertThat(stream.videoProperties()).contains(VideoStreamProperties.builder().build());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName(
      "Should keep whether the first packets carry presentation times when the probe read them")
  void shouldKeepWhetherTheFirstPacketsCarryPresentationTimesWhenTheProbeReadThem(boolean carried) {
    var video = hevc().setFirstFramesRead(true).setFirstPacketsCarryPresentationTimes(carried);

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.videoProperties())
        .flatMap(VideoStreamProperties::firstPacketsCarryPresentationTimes)
        .contains(carried);
  }

  @ParameterizedTest
  @ValueSource(strings = {"h264", "hevc", "av1"})
  @DisplayName(
      "Should keep the presentation times of the first packets when the probe reads the codec's"
          + " packets")
  void shouldKeepThePresentationTimesOfTheFirstPacketsWhenTheProbeReadsTheCodecsPackets(
      String codec) {
    var video =
        hevc()
            .setCodec(codec)
            .setFirstFramesRead(true)
            .setFirstPacketsCarryPresentationTimes(false);

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.videoProperties())
        .flatMap(VideoStreamProperties::firstPacketsCarryPresentationTimes)
        .contains(false);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should report no codec profile when the contract names one this server lacks")
  void shouldReportNoCodecProfileWhenTheContractNamesOneThisServerLacks() {
    var stream = videoOf(version5(media -> media.setStreams(0, hevc().setCodecProfileValue(99))));

    assertThat(stream.videoProperties())
        .map(VideoStreamProperties::codecProfile)
        .contains(Optional.empty());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest
  @EnumSource(
      value = com.streamarr.transcode.v1.VideoCodecProfile.class,
      names = {"VIDEO_CODEC_PROFILE_UNSPECIFIED", "UNRECOGNIZED"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Should keep the codec profile when the contract names it")
  void shouldKeepTheCodecProfileWhenTheContractNamesIt(
      com.streamarr.transcode.v1.VideoCodecProfile reported) {
    var video =
        hevc().setCodec(probedCodecOf(reported)).setCodecProfile(reported).setCodecLevel(40);

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.videoProperties().flatMap(VideoStreamProperties::codecProfile))
        .map(Enum::name)
        .contains(reported.name().substring("VIDEO_CODEC_PROFILE_".length()));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest
  @EnumSource(
      value = com.streamarr.transcode.v1.ColorPrimaries.class,
      names = {"COLOR_PRIMARIES_UNSPECIFIED", "UNRECOGNIZED"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Should keep the color primaries when the contract names them")
  void shouldKeepTheColorPrimariesWhenTheContractNamesThem(
      com.streamarr.transcode.v1.ColorPrimaries reported) {
    var stream =
        videoOf(version5(media -> media.setStreams(0, hevc().setColorPrimaries(reported))));

    assertThat(stream.videoProperties().flatMap(VideoStreamProperties::colorPrimaries))
        .map(Enum::name)
        .contains(reported.name().substring("COLOR_PRIMARIES_".length()));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest
  @EnumSource(
      value = com.streamarr.transcode.v1.TransferCharacteristics.class,
      names = {"TRANSFER_CHARACTERISTICS_UNSPECIFIED", "UNRECOGNIZED"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Should keep the transfer characteristics when the contract names them")
  void shouldKeepTheTransferCharacteristicsWhenTheContractNamesThem(
      com.streamarr.transcode.v1.TransferCharacteristics reported) {
    var video = withBase(hevc().setTransferCharacteristics(reported), signaledBase(reported));

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.videoProperties().flatMap(VideoStreamProperties::transferCharacteristics))
        .map(Enum::name)
        .contains(reported.name().substring("TRANSFER_CHARACTERISTICS_".length()));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest
  @EnumSource(
      value = com.streamarr.transcode.v1.MatrixCoefficients.class,
      names = {"MATRIX_COEFFICIENTS_UNSPECIFIED", "UNRECOGNIZED"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Should keep the matrix coefficients when the contract names them")
  void shouldKeepTheMatrixCoefficientsWhenTheContractNamesThem(
      com.streamarr.transcode.v1.MatrixCoefficients reported) {
    var stream =
        videoOf(version5(media -> media.setStreams(0, hevc().setMatrixCoefficients(reported))));

    assertThat(stream.videoProperties().flatMap(VideoStreamProperties::matrixCoefficients))
        .map(Enum::name)
        .contains(reported.name().substring("MATRIX_COEFFICIENTS_".length()));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should keep the full color range when the contract names it")
  void shouldKeepTheFullColorRangeWhenTheContractNamesIt() {
    var stream =
        videoOf(
            version5(
                media ->
                    media.setStreams(
                        0,
                        hevc()
                            .setColorRange(
                                com.streamarr.transcode.v1.ColorRange.COLOR_RANGE_FULL))));

    assertThat(stream.videoProperties().flatMap(VideoStreamProperties::colorRange))
        .contains(ColorRange.FULL);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should keep the Main tier when the contract names it")
  void shouldKeepTheMainTierWhenTheContractNamesIt() {
    var stream =
        videoOf(
            version5(
                media ->
                    media.setStreams(
                        0,
                        hevc()
                            .setCodecProfile(
                                com.streamarr.transcode.v1.VideoCodecProfile
                                    .VIDEO_CODEC_PROFILE_HEVC_MAIN)
                            .setCodecTier(
                                com.streamarr.transcode.v1.VideoCodecTier.VIDEO_CODEC_TIER_MAIN))));

    assertThat(stream.videoProperties().flatMap(VideoStreamProperties::codecTier))
        .contains(VideoCodecTier.MAIN);
  }

  @Test
  @DisplayName("Should report no video properties when a probe version 5 stream is not video")
  void shouldReportNoVideoPropertiesWhenAProbeVersion5StreamIsNotVideo() {
    var outcome =
        outcomeOf(
            version5(
                media ->
                    media.addStreams(
                        ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("audio"))));

    assertThat(outcome.streams().get(1).videoProperties()).isEmpty();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should read no probe version 5 fact when a probe version 4 result carries one")
  void shouldReadNoProbeVersion5FactWhenAProbeVersion4ResultCarriesOne() {
    var video =
        hevc()
            .setCodecProfile(
                com.streamarr.transcode.v1.VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN)
            .setFirstFramesRead(true);
    video
        .getDynamicRangeBuilder()
        .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
        .setDolbyVisionPresent(
            DolbyVisionPresent.newBuilder()
                .setObservedRpu(REPORTED_PRESENT)
                .setFirstRpuHeader(profile8RpuHeader())
                .setRpuPlacement(
                    com.streamarr.transcode.v1.DolbyVisionRpuPlacement
                        .DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE));

    var stream = videoOf(version4(media -> media.setStreams(0, video)));

    assertThat(stream.videoProperties()).isEmpty();
    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::dolbyVision)
        .contains(new DolbyVision.Present(Optional.empty(), Presence.PRESENT));
  }

  // Each case sets every field apart from its default, and from the fields that could take its
  // place, so a field that the mapper drops or swaps reads back wrong.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest(name = "{1}")
  @MethodSource("firstRpuHeaders")
  @DisplayName("Should keep each field of the first RPU header when the probe read RPUs")
  void shouldKeepEachFieldOfTheFirstRpuHeaderWhenTheProbeReadRpus(
      com.streamarr.transcode.v1.DolbyVisionRpuHeader.Builder reported,
      DolbyVisionRpuHeader expected) {
    var video =
        withDolbyVision(
            sdrHevcWithFramesRead(),
            DolbyVisionPresent.newBuilder()
                .setObservedRpu(REPORTED_PRESENT)
                .setFirstRpuHeader(reported));

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::dolbyVision)
        .map(DolbyVision.Present.class::cast)
        .flatMap(DolbyVision.Present::firstRpuHeader)
        .contains(expected);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static Stream<Arguments> firstRpuHeaders() {
    return Stream.of(
        Arguments.of(
            com.streamarr.transcode.v1.DolbyVisionRpuHeader.newBuilder()
                .setVdrRpuProfile(1)
                .setBaseLayerFullRange(true)
                .setDisableResidual(true)
                .setVdrBitDepth(12),
            DolbyVisionRpuHeader.builder()
                .vdrRpuProfile(1)
                .baseLayerFullRange(true)
                .enhancementLayerSpatialResamplingFilter(false)
                .disableResidual(true)
                .vdrBitDepth(12)
                .build()),
        Arguments.of(
            com.streamarr.transcode.v1.DolbyVisionRpuHeader.newBuilder()
                .setVdrRpuProfile(1)
                .setEnhancementLayerSpatialResamplingFilter(true)
                .setDisableResidual(true)
                .setVdrBitDepth(10),
            DolbyVisionRpuHeader.builder()
                .vdrRpuProfile(1)
                .baseLayerFullRange(false)
                .enhancementLayerSpatialResamplingFilter(true)
                .disableResidual(true)
                .vdrBitDepth(10)
                .build()));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest
  @EnumSource(
      value = com.streamarr.transcode.v1.DolbyVisionRpuPlacement.class,
      names = {
        "DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE",
        "DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE"
      })
  @DisplayName("Should keep the RPU placement when the probe read RPUs")
  void shouldKeepTheRpuPlacementWhenTheProbeReadRpus(
      com.streamarr.transcode.v1.DolbyVisionRpuPlacement placement) {
    var stream = videoOf(version5(media -> media.setStreams(0, profile8HevcWithRpus(placement))));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::dolbyVision)
        .map(DolbyVision.Present.class::cast)
        .map(DolbyVision.Present::rpuPlacement)
        .map(Enum::name)
        .contains(placement.name().substring("DOLBY_VISION_RPU_PLACEMENT_".length()));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest
  // 3 is the value of the own-picture placement that the contract removed.
  @ValueSource(ints = {3, 99})
  @DisplayName("Should read an RPU placement as unspecified when the contract does not name it")
  void shouldReadAnRpuPlacementAsUnspecifiedWhenTheContractDoesNotNameIt(int placementValue) {
    var video =
        profile8HevcWithRpus(
            com.streamarr.transcode.v1.DolbyVisionRpuPlacement
                .DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED);
    video
        .getDynamicRangeBuilder()
        .getDolbyVisionPresentBuilder()
        .setRpuPlacementValue(placementValue);

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::dolbyVision)
        .map(DolbyVision.Present.class::cast)
        .map(DolbyVision.Present::rpuPlacement)
        .contains(DolbyVisionRpuPlacement.UNSPECIFIED);
  }

  // ADR 0036: profiles 5 and 10.0 have no compatible base whatever the signaled transfer says.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest(name = "{0}")
  @MethodSource("profilesWithoutABase")
  @DisplayName("Should keep no compatible base when the Dolby Vision profile has none")
  void shouldKeepNoCompatibleBaseWhenTheDolbyVisionProfileHasNone(
      String profile, ProbeStreamInfo.Builder video) {
    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::compatibleBase)
        .contains(com.streamarr.server.domain.streaming.CompatibleBase.NONE);
  }

  static Stream<Arguments> profilesWithoutABase() {
    return Stream.of(
        Arguments.of(
            "HEVC profile 5 that the first RPU header names, tagged BT.709",
            recordLessBaselessStream("hevc", SDR_TRANSFER)),
        Arguments.of(
            "HEVC profile 5 that the first RPU header names, tagged PQ",
            recordLessBaselessStream("hevc", PQ_TRANSFER)),
        Arguments.of(
            "AV1 profile 10.0 that the first RPU header names, tagged PQ",
            recordLessBaselessStream("av1", PQ_TRANSFER)),
        Arguments.of(
            "HEVC profile 5 that the configuration record names, tagged BT.709",
            declaredBaselessStream("hevc", profile5Record())),
        Arguments.of(
            "AV1 profile 10.0 that the configuration record names, tagged BT.709",
            declaredBaselessStream("av1", profile10Record().setBaseLayerCompatibilityId(0))));
  }

  // ADR 0036: a configuration record that names a profile decides whether the stream has a base.
  // Only when the record names none does the first RPU header name profile 5 in HEVC, and 10.0 in
  // AV1, and only when it has vdr_rpu_profile 0 and bl_video_full_range_flag 1. Every other stream
  // takes its base from the signaled transfer.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest(name = "{0}")
  @MethodSource("profilesWithABase")
  @DisplayName("Should keep the base that the transfer gives when the Dolby Vision profile has one")
  void shouldKeepTheBaseThatTheTransferGivesWhenTheDolbyVisionProfileHasOne(
      String profile, ProbeStreamInfo.Builder video) {
    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::compatibleBase)
        .contains(com.streamarr.server.domain.streaming.CompatibleBase.SDR);
  }

  static Stream<Arguments> profilesWithABase() {
    return Stream.of(
        Arguments.of(
            "HEVC profile 8.2 that the configuration record names, beside a profile 5 RPU header",
            withRecord(
                sdrStreamWithRpuHeader("hevc", profile5RpuHeader()),
                profile8Record().setBaseLayerCompatibilityId(2))),
        Arguments.of(
            "AV1 profile 10.2 that the configuration record names, beside a profile 10.0 RPU"
                + " header",
            withRecord(
                sdrStreamWithRpuHeader("av1", profile5RpuHeader()),
                profile10Record().setBaseLayerCompatibilityId(2))),
        Arguments.of(
            "HEVC with RPU profile 0 and a limited-range base layer",
            sdrStreamWithRpuHeader("hevc", rpuHeader(0, false))),
        Arguments.of(
            "HEVC with RPU profile 1 and a full-range base layer",
            sdrStreamWithRpuHeader("hevc", rpuHeader(1, true))),
        Arguments.of(
            "H.264 with RPU profile 0 and a full-range base layer",
            sdrStreamWithRpuHeader("h264", rpuHeader(0, true))),
        Arguments.of(
            "VP9 with RPU profile 0 and a full-range base layer",
            sdrStreamWithRpuHeader("vp9", rpuHeader(0, true))));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName(
      "Should keep a Dolby Vision stream without a signaled transfer when its compatible base is"
          + " none")
  void shouldKeepADolbyVisionStreamWithoutASignaledTransferWhenItsCompatibleBaseIsNone() {
    var video = profile8RpusWithoutTransfer(hevc(), CompatibleBase.COMPATIBLE_BASE_NONE);

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::compatibleBase)
        .contains(com.streamarr.server.domain.streaming.CompatibleBase.NONE);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest
  @EnumSource(
      value = com.streamarr.transcode.v1.TransferCharacteristics.class,
      names = {
        "TRANSFER_CHARACTERISTICS_UNSPECIFIED",
        "TRANSFER_CHARACTERISTICS_LINEAR",
        "TRANSFER_CHARACTERISTICS_LOG100",
        "TRANSFER_CHARACTERISTICS_LOG316",
        "TRANSFER_CHARACTERISTICS_SMPTE428"
      })
  @DisplayName(
      "Should keep a Dolby Vision stream whose transfer names no standard format when its"
          + " compatible base is none")
  void shouldKeepADolbyVisionStreamWhoseTransferNamesNoStandardFormatWhenItsCompatibleBaseIsNone(
      com.streamarr.transcode.v1.TransferCharacteristics transfer) {
    var video =
        profile8RpusWithoutTransfer(hevc(), CompatibleBase.COMPATIBLE_BASE_NONE)
            .setTransferCharacteristics(transfer);

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::compatibleBase)
        .contains(com.streamarr.server.domain.streaming.CompatibleBase.NONE);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest
  @EnumSource(
      value = com.streamarr.transcode.v1.TransferCharacteristics.class,
      names = {
        "TRANSFER_CHARACTERISTICS_UNSPECIFIED",
        "TRANSFER_CHARACTERISTICS_LINEAR",
        "TRANSFER_CHARACTERISTICS_LOG100",
        "TRANSFER_CHARACTERISTICS_LOG316",
        "TRANSFER_CHARACTERISTICS_SMPTE428"
      })
  @DisplayName(
      "Should keep a stream without Dolby Vision whose transfer names no standard format when its"
          + " base is unspecified")
  void
      shouldKeepAStreamWithoutDolbyVisionWhoseTransferNamesNoStandardFormatWhenItsBaseIsUnspecified(
          com.streamarr.transcode.v1.TransferCharacteristics transfer) {
    var video =
        withBase(
            hevc().setTransferCharacteristics(transfer),
            CompatibleBase.COMPATIBLE_BASE_UNSPECIFIED);

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::compatibleBase)
        .contains(com.streamarr.server.domain.streaming.CompatibleBase.UNSPECIFIED);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("declaredProfile10Bases")
  @DisplayName("Should keep a declared profile 10 stream when its record names a compatible base")
  void shouldKeepADeclaredProfile10StreamWhenItsRecordNamesACompatibleBase(
      DeclaredProfile10Base declared) {
    var video =
        withDolbyVision(
            withBase(av1().setTransferCharacteristics(declared.transfer()), declared.base()),
            DolbyVisionPresent.newBuilder().setDeclared(declared.record()));

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::compatibleBase)
        .map(Enum::name)
        .contains(declared.base().name().substring("COMPATIBLE_BASE_".length()));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static Stream<DeclaredProfile10Base> declaredProfile10Bases() {
    return Stream.of(
        DeclaredProfile10Base.builder()
            .description("profile 10.1 with a PQ base")
            .record(profile10Record().setBaseLayerCompatibilityId(1))
            .transfer(PQ_TRANSFER)
            .base(CompatibleBase.COMPATIBLE_BASE_PQ)
            .build(),
        DeclaredProfile10Base.builder()
            .description("profile 10.4 with an HLG base")
            .record(profile10Record().setBaseLayerCompatibilityId(4))
            .transfer(
                com.streamarr.transcode.v1.TransferCharacteristics
                    .TRANSFER_CHARACTERISTICS_ARIB_STD_B67)
            .base(CompatibleBase.COMPATIBLE_BASE_HLG)
            .build(),
        DeclaredProfile10Base.builder()
            .description("profile 10 without a compatibility identifier and with an SDR base")
            .record(profile10Record())
            .transfer(SDR_TRANSFER)
            .base(CompatibleBase.COMPATIBLE_BASE_SDR)
            .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("violations")
  @DisplayName(
      "Should accept the stream that a contract violation starts from when no fact breaks it")
  void shouldAcceptTheStreamThatAContractViolationStartsFromWhenNoFactBreaksIt(
      ContractViolation violation) {
    var report = report(version5(media -> media.setStreams(0, violation.validStream())));

    assertThatCode(() -> mapper.map(report)).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("violations")
  @DisplayName("Should reject the result when one fact breaks the probe version 5 contract")
  void shouldRejectTheResultWhenOneFactBreaksTheProbeVersion5Contract(ContractViolation violation) {
    var report = report(version5(media -> media.setStreams(0, violation.brokenStream())));

    assertThatThrownBy(() -> mapper.map(report))
        .isInstanceOf(ProbeContractViolationException.class);
  }

  // Each violation starts from a stream that maps and changes one fact, so the rule that guards
  // that fact is the only one that can reject it.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static Stream<ContractViolation> violations() {
    var mainTier = com.streamarr.transcode.v1.VideoCodecTier.VIDEO_CODEC_TIER_MAIN;
    var h264High = com.streamarr.transcode.v1.VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_HIGH;
    var hevcMain = com.streamarr.transcode.v1.VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN;
    var hevcMain10 = com.streamarr.transcode.v1.VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN_10;
    var av1Main = com.streamarr.transcode.v1.VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN;
    var hlgTransfer =
        com.streamarr.transcode.v1.TransferCharacteristics.TRANSFER_CHARACTERISTICS_ARIB_STD_B67;
    var linearTransfer =
        com.streamarr.transcode.v1.TransferCharacteristics.TRANSFER_CHARACTERISTICS_LINEAR;
    var log100Transfer =
        com.streamarr.transcode.v1.TransferCharacteristics.TRANSFER_CHARACTERISTICS_LOG100;
    var log316Transfer =
        com.streamarr.transcode.v1.TransferCharacteristics.TRANSFER_CHARACTERISTICS_LOG316;
    var smpte428Transfer =
        com.streamarr.transcode.v1.TransferCharacteristics.TRANSFER_CHARACTERISTICS_SMPTE428;
    var bt2020TenBitTransfer =
        com.streamarr.transcode.v1.TransferCharacteristics.TRANSFER_CHARACTERISTICS_BT2020_10;
    var sdrBase = CompatibleBase.COMPATIBLE_BASE_SDR;
    var pqBase = CompatibleBase.COMPATIBLE_BASE_PQ;
    var hlgBase = CompatibleBase.COMPATIBLE_BASE_HLG;
    var noBase = CompatibleBase.COMPATIBLE_BASE_NONE;
    var unspecifiedBase = CompatibleBase.COMPATIBLE_BASE_UNSPECIFIED;
    return Stream.of(
        violation(
            "a codec level without a codec profile", sdrHevc(), stream -> stream.setCodecLevel(41)),
        violation(
            "a codec tier without a codec profile",
            sdrHevc(),
            stream -> stream.setCodecTier(mainTier)),
        violation(
            "a codec tier on an H.264 codec profile",
            sdrHevc().setCodec("h264").setCodecProfile(h264High),
            stream -> stream.setCodecTier(mainTier)),
        violation(
            "an HEVC codec profile on an H.264 stream",
            sdrHevc().setCodec("h264"),
            stream -> stream.setCodecProfile(hevcMain10)),
        violation(
            "an H.264 codec profile on an AV1 stream",
            sdrHevc().setCodec("av1"),
            stream -> stream.setCodecProfile(h264High)),
        violation(
            "an AV1 codec profile on an HEVC stream",
            sdrHevc(),
            stream -> stream.setCodecProfile(av1Main)),
        violation(
            "a codec profile on a VP9 stream",
            sdrHevc().setCodec("vp9"),
            stream -> stream.setCodecProfile(av1Main)),
        violation(
            "a codec profile on a stream without a codec",
            sdrHevc().clearCodec(),
            stream -> stream.setCodecProfile(hevcMain)),
        violation(
            "a codec level of 0",
            sdrHevc().setCodecProfile(hevcMain),
            stream -> stream.setCodecLevel(0)),
        violation(
            "a codec level beyond the integer range",
            sdrHevc().setCodecProfile(hevcMain),
            stream -> stream.setCodecLevel(-1)),
        violation(
            "presentation times on packets of a stream whose frames the probe did not read",
            sdrHevc(),
            stream -> stream.setFirstPacketsCarryPresentationTimes(true)),
        violation(
            "presentation times on packets of a VP9 stream",
            sdrHevc().setCodec("vp9").setFirstFramesRead(true),
            stream -> stream.setFirstPacketsCarryPresentationTimes(false)),
        violation(
            "presentation times on packets of a stream without a codec",
            sdrHevc().clearCodec().setFirstFramesRead(true),
            stream -> stream.setFirstPacketsCarryPresentationTimes(true)),
        violation(
            "a PQ transfer with an SDR compatible base",
            sdrHevc(),
            stream -> stream.setTransferCharacteristics(PQ_TRANSFER)),
        violation(
            "an HLG transfer with a PQ compatible base",
            pqHevc(),
            stream -> stream.setTransferCharacteristics(hlgTransfer)),
        violation(
            "a PQ compatible base with a BT.709 transfer",
            sdrHevc(),
            stream -> withBase(stream, pqBase)),
        violation(
            "an unspecified base with a BT.709 transfer",
            sdrHevc(),
            stream -> withBase(stream, unspecifiedBase)),
        violation(
            "an HLG compatible base without a signaled transfer",
            hevc(),
            stream -> withBase(stream, hlgBase)),
        violation(
            "an SDR base without a signaled transfer or Dolby Vision",
            hevc(),
            stream -> withBase(stream, sdrBase)),
        violation(
            "an SDR base with a linear transfer",
            hevc().setTransferCharacteristics(linearTransfer),
            stream -> withBase(stream, sdrBase)),
        violation(
            "no compatible base with a logarithmic transfer and without Dolby Vision",
            hevc().setTransferCharacteristics(log100Transfer),
            stream -> withBase(stream, noBase)),
        violation(
            "an SDR base beside Dolby Vision without a signaled transfer",
            profile8RpusWithoutTransfer(hevc(), noBase),
            stream -> withBase(stream, sdrBase)),
        violation(
            "an unspecified base beside Dolby Vision without a signaled transfer",
            profile8RpusWithoutTransfer(hevc(), noBase),
            stream -> withBase(stream, unspecifiedBase)),
        violation(
            "an SDR base beside Dolby Vision with a transfer that the contract does not name",
            profile8RpusWithoutTransfer(hevc(), noBase).setTransferCharacteristicsValue(999),
            stream -> withBase(stream, sdrBase)),
        violation(
            "an SDR base beside Dolby Vision with a logarithmic transfer",
            profile8RpusWithoutTransfer(hevc(), noBase).setTransferCharacteristics(log316Transfer),
            stream -> withBase(stream, sdrBase)),
        violation(
            "an unspecified base beside Dolby Vision with an SMPTE ST 428 transfer",
            profile8RpusWithoutTransfer(hevc(), noBase)
                .setTransferCharacteristics(smpte428Transfer),
            stream -> withBase(stream, unspecifiedBase)),
        violation(
            "an unspecified base with a BT.2020 10-bit transfer beside Dolby Vision",
            profile8RpusWithoutTransfer(hevc(), sdrBase)
                .setTransferCharacteristics(bt2020TenBitTransfer),
            stream -> withBase(stream, unspecifiedBase)),
        violation(
            "no compatible base with a BT.709 transfer beside Dolby Vision profile 8",
            sdrProfile82Hevc(),
            stream -> withBase(stream, noBase)),
        violation(
            "a PQ transfer with the SDR base that a Dolby Vision profile 8.2 record names",
            sdrProfile82Hevc(),
            stream -> stream.setTransferCharacteristics(PQ_TRANSFER)),
        violation(
            "an omitted mastering display that the stream still lists",
            pqHevc().setMasteringDisplay(WorkerProbeResultFixture.masteringDisplayBuilder()),
            stream -> stream.setMasteringDisplayOmitted(true)),
        violation(
            "an omitted content light level that the stream still lists",
            pqHevc().setContentLightLevel(ContentLightLevel.getDefaultInstance()),
            stream -> stream.setContentLightLevelOmitted(true)),
        violation(
            "HDR10+ absent without reading frames",
            withHdr10Plus(sdrHevcWithFramesRead(), REPORTED_ABSENT),
            stream -> stream.setFirstFramesRead(false)),
        violation(
            "HDR10+ present without reading frames",
            withHdr10Plus(sdrHevcWithFramesRead(), REPORTED_PRESENT),
            stream -> stream.setFirstFramesRead(false)),
        violation(
            "Dolby Vision absent without reading frames",
            withoutDolbyVision(sdrHevcWithFramesRead()),
            stream -> stream.setFirstFramesRead(false)),
        violation(
            "an observed RPU absent without reading frames",
            withDolbyVision(
                sdrHevcWithFramesRead(),
                DolbyVisionPresent.newBuilder().setObservedRpu(REPORTED_ABSENT)),
            stream -> stream.setFirstFramesRead(false)),
        violation(
            "an observed RPU present without reading frames",
            profile8HevcWithRpus(
                com.streamarr.transcode.v1.DolbyVisionRpuPlacement
                    .DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED),
            stream -> stream.setFirstFramesRead(false)),
        violation(
            "a first RPU header without an observed RPU",
            withDolbyVision(
                sdrHevcWithFramesRead(),
                DolbyVisionPresent.newBuilder().setObservedRpu(REPORTED_ABSENT)),
            stream -> {
              dolbyVisionOf(stream).setFirstRpuHeader(profile8RpuHeader());
              return stream;
            }),
        violation(
            "an RPU placement beside an absent observed RPU",
            withDolbyVision(
                sdrHevcWithFramesRead(),
                DolbyVisionPresent.newBuilder().setObservedRpu(REPORTED_ABSENT)),
            stream -> {
              dolbyVisionOf(stream).setRpuPlacement(REPORTED_STARTS_SAMPLE);
              return stream;
            }),
        violation(
            "an RPU placement beside an unspecified observed RPU",
            withDolbyVision(sdrHevcWithFramesRead(), DolbyVisionPresent.newBuilder()),
            stream -> {
              dolbyVisionOf(stream).setRpuPlacement(REPORTED_STARTS_SAMPLE);
              return stream;
            }),
        violation(
            "an RPU bit depth below 8",
            profile8HevcWithRpus(
                com.streamarr.transcode.v1.DolbyVisionRpuPlacement
                    .DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED),
            stream -> {
              dolbyVisionOf(stream).getFirstRpuHeaderBuilder().setVdrBitDepth(7);
              return stream;
            }),
        violation(
            "an RPU profile beyond the integer range",
            profile8HevcWithRpus(
                com.streamarr.transcode.v1.DolbyVisionRpuPlacement
                    .DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED),
            stream -> {
              dolbyVisionOf(stream).getFirstRpuHeaderBuilder().setVdrRpuProfile(-1);
              return stream;
            }),
        violation(
            "a record-less profile 5 stream with an SDR compatible base",
            recordLessBaselessStream("hevc", SDR_TRANSFER),
            stream -> withBase(stream, sdrBase)),
        violation(
            "a profile 5 RPU header beside a record without a profile and with an SDR base",
            withRecord(recordLessBaselessStream("hevc", SDR_TRANSFER), profilelessRecord()),
            stream -> withBase(stream, sdrBase)),
        violation(
            "a declared profile 5 stream with an SDR compatible base",
            declaredBaselessStream("hevc", profile5Record()),
            stream -> withBase(stream, sdrBase)),
        violation(
            "a record-less AV1 profile 10.0 stream with an SDR compatible base",
            recordLessBaselessStream("av1", SDR_TRANSFER),
            stream -> withBase(stream, sdrBase)),
        violation(
            "an AV1 profile 10.0 RPU header beside a record without a profile and with an SDR base",
            withRecord(recordLessBaselessStream("av1", SDR_TRANSFER), profilelessRecord()),
            stream -> withBase(stream, sdrBase)),
        violation(
            "a declared profile 10.0 stream with a PQ compatible base",
            declaredBaselessStream("av1", profile10Record().setBaseLayerCompatibilityId(0))
                .setTransferCharacteristics(PQ_TRANSFER),
            stream -> withBase(stream, pqBase)),
        violation(
            "a declared profile 8.2 stream beside a profile 5 RPU header without a compatible base",
            withRecord(
                sdrStreamWithRpuHeader("hevc", profile5RpuHeader()),
                profile8Record().setBaseLayerCompatibilityId(2)),
            stream -> withBase(stream, noBase)),
        violation(
            "an RPU placement on an AV1 stream",
            withDolbyVision(
                pqHevc().setCodec("av1").setFirstFramesRead(true),
                DolbyVisionPresent.newBuilder()
                    .setDeclared(profile10Record().setBaseLayerCompatibilityId(1))
                    .setObservedRpu(REPORTED_PRESENT)),
            stream -> {
              dolbyVisionOf(stream).setRpuPlacement(REPORTED_STARTS_SAMPLE);
              return stream;
            }),
        violation(
            "HDR10+ absent on a VP9 stream",
            sdrHevcWithFramesRead().setCodec("vp9"),
            stream -> withHdr10Plus(stream, REPORTED_ABSENT)),
        violation(
            "an RPU placement on a VP9 stream",
            withDolbyVision(
                pqHevc().setCodec("vp9").setFirstFramesRead(true),
                DolbyVisionPresent.newBuilder().setObservedRpu(REPORTED_PRESENT)),
            stream -> {
              dolbyVisionOf(stream).setRpuPlacement(REPORTED_STARTS_SAMPLE);
              return stream;
            }),
        violation(
            "an observed RPU absent on a VP9 stream",
            withDolbyVision(
                pqHevc().setCodec("vp9").setFirstFramesRead(true), DolbyVisionPresent.newBuilder()),
            stream -> {
              dolbyVisionOf(stream).setObservedRpu(REPORTED_ABSENT);
              return stream;
            }),
        violation(
            "Dolby Vision absent on an MPEG-2 stream",
            sdrHevcWithFramesRead().setCodec("mpeg2video"),
            RemoteProbeResultStreamPropertiesMapperTest::withoutDolbyVision),
        violation(
            "HDR10+ absent on a stream without a codec",
            sdrHevcWithFramesRead().clearCodec(),
            stream -> withHdr10Plus(stream, REPORTED_ABSENT)));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should keep HDR10+ present on a VP9 stream when its frames show HDR10+")
  void shouldKeepHdr10PlusPresentOnAVp9StreamWhenItsFramesShowHdr10Plus() {
    var video = hevc().setCodec("vp9").setFirstFramesRead(true);
    video.getDynamicRangeBuilder().setHdr10Plus(REPORTED_PRESENT);

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange()).map(VideoDynamicRange::hdr10Plus).contains(Presence.PRESENT);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName(
      "Should keep an observed RPU and its header on a VP9 stream when its frames show one")
  void shouldKeepAnObservedRpuAndItsHeaderOnAVp9StreamWhenItsFramesShowOne() {
    var video =
        profile8RpusWithoutTransfer(hevc().setCodec("vp9"), CompatibleBase.COMPATIBLE_BASE_NONE);

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::dolbyVision)
        .get()
        .isInstanceOfSatisfying(
            DolbyVision.Present.class,
            present -> {
              assertThat(present.observedRpu()).isEqualTo(Presence.PRESENT);
              assertThat(present.firstRpuHeader()).isPresent();
              assertThat(present.rpuPlacement()).isEqualTo(DolbyVisionRpuPlacement.UNSPECIFIED);
            });
  }

  @Test
  @DisplayName(
      "Should keep a VP9 stream whose frames the probe read when it names no frame feature")
  void shouldKeepAVp9StreamWhoseFramesTheProbeReadWhenItNamesNoFrameFeature() {
    var video = hevc().setCodec("vp9").setFirstFramesRead(true);

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.videoProperties()).map(VideoStreamProperties::firstFramesRead).contains(true);
  }

  @Test
  @DisplayName(
      "Should keep a chosen AV1 stream that lacks static HDR metadata when it flags no omission")
  void shouldKeepAChosenAv1StreamThatLacksStaticHdrMetadataWhenItFlagsNoOmission() {
    var video = av1().setFirstFramesRead(true);

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.masteringDisplay()).isEmpty();
    assertThat(stream.contentLightLevel()).isEmpty();
    assertThat(stream.videoProperties())
        .contains(VideoStreamProperties.builder().firstFramesRead(true).build());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = com.streamarr.transcode.v1.Presence.class,
      names = {"PRESENCE_PRESENT", "PRESENCE_ABSENT"})
  @DisplayName("Should keep the HDR10+ presence of an H.264 stream when the probe read its frames")
  void shouldKeepTheHdr10PlusPresenceOfAnH264StreamWhenTheProbeReadItsFrames(
      com.streamarr.transcode.v1.Presence hdr10Plus) {
    var video = withHdr10Plus(hevc().setCodec("h264").setFirstFramesRead(true), hdr10Plus);

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::hdr10Plus)
        .map(Enum::name)
        .contains(hdr10Plus.name().substring("PRESENCE_".length()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("visibleAbsences")
  @DisplayName("Should keep an absence when the probe can see the feature in the stream's codec")
  void shouldKeepAnAbsenceWhenTheProbeCanSeeTheFeatureInTheStreamsCodec(VisibleAbsence absence) {
    var stream = videoOf(version5(media -> media.setStreams(0, absence.stream())));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::dolbyVision)
        .contains(absence.dolbyVision());
    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::hdr10Plus)
        .contains(absence.hdr10Plus());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static Stream<VisibleAbsence> visibleAbsences() {
    var h264WithoutRpus =
        withDolbyVision(
            withBase(
                hevc()
                    .setCodec("h264")
                    .setTransferCharacteristics(SDR_TRANSFER)
                    .setFirstFramesRead(true),
                CompatibleBase.COMPATIBLE_BASE_SDR),
            DolbyVisionPresent.newBuilder()
                .setDeclared(DolbyVisionConfiguration.newBuilder().setProfile(9))
                .setObservedRpu(REPORTED_ABSENT));
    var av1WithoutRpus =
        withDolbyVision(
            withBase(
                av1().setTransferCharacteristics(PQ_TRANSFER).setFirstFramesRead(true),
                CompatibleBase.COMPATIBLE_BASE_PQ),
            DolbyVisionPresent.newBuilder()
                .setDeclared(DolbyVisionConfiguration.newBuilder().setProfile(10))
                .setObservedRpu(REPORTED_ABSENT));
    return Stream.of(
        VisibleAbsence.builder().description("Dolby Vision absent on an H.264 stream").stream(
                withoutDolbyVision(hevc().setCodec("h264").setFirstFramesRead(true)))
            .dolbyVision(new DolbyVision.Absent())
            .hdr10Plus(Presence.UNSPECIFIED)
            .build(),
        VisibleAbsence.builder().description("an observed RPU absent on an H.264 stream").stream(
                h264WithoutRpus)
            .dolbyVision(
                new DolbyVision.Present(
                    Optional.of(
                        com.streamarr.server.domain.streaming.DolbyVisionConfiguration.builder()
                            .profile(OptionalInt.of(9))
                            .build()),
                    Presence.ABSENT))
            .hdr10Plus(Presence.UNSPECIFIED)
            .build(),
        VisibleAbsence.builder()
            .description("HDR10+ and Dolby Vision absent on an HEVC stream")
            .stream(
                withoutDolbyVision(withHdr10Plus(hevc().setFirstFramesRead(true), REPORTED_ABSENT)))
            .dolbyVision(new DolbyVision.Absent())
            .hdr10Plus(Presence.ABSENT)
            .build(),
        VisibleAbsence.builder().description("an observed RPU absent on an AV1 stream").stream(
                av1WithoutRpus)
            .dolbyVision(
                new DolbyVision.Present(
                    Optional.of(
                        com.streamarr.server.domain.streaming.DolbyVisionConfiguration.builder()
                            .profile(OptionalInt.of(10))
                            .build()),
                    Presence.ABSENT))
            .hdr10Plus(Presence.UNSPECIFIED)
            .build(),
        VisibleAbsence.builder()
            .description("HDR10+ and Dolby Vision absent on an AV1 stream")
            .stream(
                withoutDolbyVision(withHdr10Plus(av1().setFirstFramesRead(true), REPORTED_ABSENT)))
            .dolbyVision(new DolbyVision.Absent())
            .hdr10Plus(Presence.ABSENT)
            .build());
  }

  @Test
  @DisplayName("Should reject the result when a stream other than the chosen one read frames")
  void shouldRejectTheResultWhenAStreamOtherThanTheChosenOneReadFrames() {
    var unread = report(version5(media -> media.addStreams(secondVideoStream())));
    var read =
        report(version5(media -> media.addStreams(secondVideoStream().setFirstFramesRead(true))));

    assertThatCode(() -> mapper.map(unread)).doesNotThrowAnyException();
    assertThatThrownBy(() -> mapper.map(read)).isInstanceOf(ProbeContractViolationException.class);
  }

  // ADR 0036: the compatible base of every video stream follows the probe version 5 rules.
  @Test
  @DisplayName(
      "Should reject the result when another video stream has a base that its transfer"
          + " contradicts")
  void shouldRejectTheResultWhenAnotherVideoStreamHasABaseThatItsTransferContradicts() {
    var agreeing = report(version5(media -> media.addStreams(sdrHevc().setIndex(1))));
    var contradicting =
        report(
            version5(
                media ->
                    media.addStreams(
                        withBase(sdrHevc().setIndex(1), CompatibleBase.COMPATIBLE_BASE_PQ))));

    assertThatCode(() -> mapper.map(agreeing)).doesNotThrowAnyException();
    assertThatThrownBy(() -> mapper.map(contradicting))
        .isInstanceOf(ProbeContractViolationException.class);
  }

  /** A stream that maps, and the one fact that breaks the probe version 5 contract. */
  record ContractViolation(
      @NonNull String description,
      @NonNull ProbeStreamInfo.Builder valid,
      @NonNull UnaryOperator<ProbeStreamInfo.Builder> breakContract) {

    ProbeStreamInfo validStream() {
      return valid.build();
    }

    ProbeStreamInfo brokenStream() {
      return breakContract.apply(valid.clone()).build();
    }

    @Override
    public String toString() {
      return description;
    }
  }

  @Builder
  record DeclaredProfile10Base(
      @NonNull String description,
      @NonNull DolbyVisionConfiguration.Builder record,
      @SuppressWarnings("checkstyle:fullyQualifiedName") @NonNull
          com.streamarr.transcode.v1.TransferCharacteristics transfer,
      @NonNull CompatibleBase base) {

    @Override
    public String toString() {
      return description;
    }
  }

  @Builder
  record VisibleAbsence(
      @NonNull String description,
      @NonNull ProbeStreamInfo.Builder stream,
      @NonNull DolbyVision dolbyVision,
      @NonNull Presence hdr10Plus) {

    @Override
    public String toString() {
      return description;
    }
  }

  private static ContractViolation violation(
      String description,
      ProbeStreamInfo.Builder valid,
      UnaryOperator<ProbeStreamInfo.Builder> breakContract) {
    return new ContractViolation(description, valid, breakContract);
  }

  private static ProbeStreamInfo.Builder hevc() {
    return ProbeStreamInfo.newBuilder().setIndex(0).setCodecType("video").setCodec("hevc");
  }

  private static ProbeStreamInfo.Builder av1() {
    return hevc().setCodec("av1");
  }

  private static ProbeStreamInfo.Builder sdrHevc() {
    return withBase(
        hevc().setTransferCharacteristics(SDR_TRANSFER), CompatibleBase.COMPATIBLE_BASE_SDR);
  }

  private static ProbeStreamInfo.Builder sdrHevcWithFramesRead() {
    return sdrHevc().setFirstFramesRead(true);
  }

  private static ProbeStreamInfo.Builder pqHevc() {
    return withBase(
        hevc().setTransferCharacteristics(PQ_TRANSFER), CompatibleBase.COMPATIBLE_BASE_PQ);
  }

  // Dolby Vision profile 8.2: a record that names profile 8 and the SDR base of identifier 2.
  private static ProbeStreamInfo.Builder sdrProfile82Hevc() {
    return withDolbyVision(
        sdrHevc(),
        DolbyVisionPresent.newBuilder()
            .setDeclared(
                DolbyVisionConfiguration.newBuilder()
                    .setProfile(8)
                    .setBaseLayerCompatibilityId(2)
                    .setBaseLayer(true)
                    .setRpu(true)));
  }

  private static ProbeStreamInfo.Builder secondVideoStream() {
    return ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("video");
  }

  private static ProbeStreamInfo.Builder withBase(
      ProbeStreamInfo.Builder stream, CompatibleBase base) {
    stream.getDynamicRangeBuilder().setCompatibleBase(base);
    return stream;
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static ProbeStreamInfo.Builder withHdr10Plus(
      ProbeStreamInfo.Builder stream, com.streamarr.transcode.v1.Presence hdr10Plus) {
    stream.getDynamicRangeBuilder().setHdr10Plus(hdr10Plus);
    return stream;
  }

  private static ProbeStreamInfo.Builder withDolbyVision(
      ProbeStreamInfo.Builder stream, DolbyVisionPresent.Builder present) {
    stream.getDynamicRangeBuilder().setDolbyVisionPresent(present);
    return stream;
  }

  private static ProbeStreamInfo.Builder withoutDolbyVision(ProbeStreamInfo.Builder stream) {
    stream.getDynamicRangeBuilder().setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance());
    return stream;
  }

  private static ProbeStreamInfo.Builder withRecord(
      ProbeStreamInfo.Builder stream, DolbyVisionConfiguration.Builder record) {
    dolbyVisionOf(stream).setDeclared(record);
    return stream;
  }

  private static DolbyVisionPresent.Builder dolbyVisionOf(ProbeStreamInfo.Builder stream) {
    return stream.getDynamicRangeBuilder().getDolbyVisionPresentBuilder();
  }

  // ADR 0036 lists the compatible base that each signaled transfer gives a stream without Dolby
  // Vision. A transfer that names no standard format gives none.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static CompatibleBase signaledBase(
      com.streamarr.transcode.v1.TransferCharacteristics transfer) {
    return switch (transfer) {
      case TRANSFER_CHARACTERISTICS_BT709,
          TRANSFER_CHARACTERISTICS_BT470M,
          TRANSFER_CHARACTERISTICS_BT470BG,
          TRANSFER_CHARACTERISTICS_SMPTE170M,
          TRANSFER_CHARACTERISTICS_SMPTE240M,
          TRANSFER_CHARACTERISTICS_IEC61966_2_4,
          TRANSFER_CHARACTERISTICS_BT1361,
          TRANSFER_CHARACTERISTICS_IEC61966_2_1,
          TRANSFER_CHARACTERISTICS_BT2020_10,
          TRANSFER_CHARACTERISTICS_BT2020_12 ->
          CompatibleBase.COMPATIBLE_BASE_SDR;
      case TRANSFER_CHARACTERISTICS_SMPTE2084 -> CompatibleBase.COMPATIBLE_BASE_PQ;
      case TRANSFER_CHARACTERISTICS_ARIB_STD_B67 -> CompatibleBase.COMPATIBLE_BASE_HLG;
      case TRANSFER_CHARACTERISTICS_UNSPECIFIED,
          TRANSFER_CHARACTERISTICS_LINEAR,
          TRANSFER_CHARACTERISTICS_LOG100,
          TRANSFER_CHARACTERISTICS_LOG316,
          TRANSFER_CHARACTERISTICS_SMPTE428,
          UNRECOGNIZED ->
          CompatibleBase.COMPATIBLE_BASE_UNSPECIFIED;
    };
  }

  // The codec name that the probe stores for a stream of each codec profile that the contract
  // names.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static String probedCodecOf(com.streamarr.transcode.v1.VideoCodecProfile profile) {
    return switch (profile) {
      case VIDEO_CODEC_PROFILE_H264_CONSTRAINED_BASELINE,
          VIDEO_CODEC_PROFILE_H264_BASELINE,
          VIDEO_CODEC_PROFILE_H264_MAIN,
          VIDEO_CODEC_PROFILE_H264_EXTENDED,
          VIDEO_CODEC_PROFILE_H264_HIGH,
          VIDEO_CODEC_PROFILE_H264_HIGH_10,
          VIDEO_CODEC_PROFILE_H264_HIGH_422,
          VIDEO_CODEC_PROFILE_H264_HIGH_444_PREDICTIVE ->
          "h264";
      case VIDEO_CODEC_PROFILE_HEVC_MAIN,
          VIDEO_CODEC_PROFILE_HEVC_MAIN_10,
          VIDEO_CODEC_PROFILE_HEVC_MAIN_STILL_PICTURE,
          VIDEO_CODEC_PROFILE_HEVC_FORMAT_RANGE_EXTENSIONS ->
          "hevc";
      case VIDEO_CODEC_PROFILE_AV1_MAIN,
          VIDEO_CODEC_PROFILE_AV1_HIGH,
          VIDEO_CODEC_PROFILE_AV1_PROFESSIONAL ->
          "av1";
      case VIDEO_CODEC_PROFILE_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("The contract names no codec for " + profile);
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static ProbeStreamInfo.Builder profile8RpusWithoutTransfer(
      ProbeStreamInfo.Builder stream, CompatibleBase base) {
    return withDolbyVision(
        withBase(stream.setFirstFramesRead(true), base),
        DolbyVisionPresent.newBuilder()
            .setObservedRpu(REPORTED_PRESENT)
            .setFirstRpuHeader(profile8RpuHeader()));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static ProbeStreamInfo.Builder profile8HevcWithRpus(
      com.streamarr.transcode.v1.DolbyVisionRpuPlacement placement) {
    return withDolbyVision(
        sdrHevcWithFramesRead(),
        DolbyVisionPresent.newBuilder()
            .setObservedRpu(REPORTED_PRESENT)
            .setFirstRpuHeader(profile8RpuHeader())
            .setRpuPlacement(placement));
  }

  // A stream whose configuration record names no profile and whose first RPU header has profile 0
  // and a full-range base layer: profile 5 in HEVC, and profile 10.0 in AV1.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static ProbeStreamInfo.Builder recordLessBaselessStream(
      String codec, com.streamarr.transcode.v1.TransferCharacteristics transfer) {
    return withDolbyVision(
        withBase(
            hevc().setCodec(codec).setTransferCharacteristics(transfer).setFirstFramesRead(true),
            CompatibleBase.COMPATIBLE_BASE_NONE),
        DolbyVisionPresent.newBuilder()
            .setObservedRpu(REPORTED_PRESENT)
            .setFirstRpuHeader(profile5RpuHeader()));
  }

  // A stream tagged BT.709 whose configuration record names profile 5 or profile 10.0.
  private static ProbeStreamInfo.Builder declaredBaselessStream(
      String codec, DolbyVisionConfiguration.Builder record) {
    return withDolbyVision(
        withBase(
            hevc().setCodec(codec).setTransferCharacteristics(SDR_TRANSFER),
            CompatibleBase.COMPATIBLE_BASE_NONE),
        DolbyVisionPresent.newBuilder().setDeclared(record));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static ProbeStreamInfo.Builder sdrStreamWithRpuHeader(
      String codec, com.streamarr.transcode.v1.DolbyVisionRpuHeader.Builder header) {
    return withDolbyVision(
        sdrHevcWithFramesRead().setCodec(codec),
        DolbyVisionPresent.newBuilder().setObservedRpu(REPORTED_PRESENT).setFirstRpuHeader(header));
  }

  private static DolbyVisionConfiguration.Builder profilelessRecord() {
    return DolbyVisionConfiguration.newBuilder().setBaseLayer(true).setRpu(true);
  }

  private static DolbyVisionConfiguration.Builder profile5Record() {
    return DolbyVisionConfiguration.newBuilder().setProfile(5).setBaseLayer(true).setRpu(true);
  }

  private static DolbyVisionConfiguration.Builder profile8Record() {
    return DolbyVisionConfiguration.newBuilder().setProfile(8).setBaseLayer(true).setRpu(true);
  }

  private static DolbyVisionConfiguration.Builder profile10Record() {
    return DolbyVisionConfiguration.newBuilder().setProfile(10).setBaseLayer(true).setRpu(true);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.DolbyVisionRpuHeader.Builder profile8RpuHeader() {
    return rpuHeader(1, false);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.DolbyVisionRpuHeader.Builder profile5RpuHeader() {
    return rpuHeader(0, true);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.DolbyVisionRpuHeader.Builder rpuHeader(
      int vdrRpuProfile, boolean baseLayerFullRange) {
    return com.streamarr.transcode.v1.DolbyVisionRpuHeader.newBuilder()
        .setVdrRpuProfile(vdrRpuProfile)
        .setBaseLayerFullRange(baseLayerFullRange)
        .setDisableResidual(true)
        .setVdrBitDepth(12);
  }

  private StreamInfo videoOf(ProbeAttemptResult result) {
    return outcomeOf(result).streams().getFirst();
  }

  private ProbeOutcome.Success outcomeOf(ProbeAttemptResult result) {
    return (ProbeOutcome.Success) mapper.map(report(result));
  }

  private static ProbeAttemptResult version5(UnaryOperator<ProbeMediaInfo.Builder> media) {
    return result(PROBE_VERSION_5, media);
  }

  private static ProbeAttemptResult version4(UnaryOperator<ProbeMediaInfo.Builder> media) {
    return result(PROBE_VERSION_4, media);
  }

  private static ProbeAttemptResult result(
      int probeVersion, UnaryOperator<ProbeMediaInfo.Builder> media) {
    return ProbeAttemptResult.newBuilder()
        .setProbeVersion(probeVersion)
        .setMedia(media.apply(WorkerProbeResultFixture.version4MediaBuilder()))
        .build();
  }

  private static ProbeAttemptReport report(ProbeAttemptResult result) {
    return new ProbeAttemptReport(result, List.of());
  }
}
