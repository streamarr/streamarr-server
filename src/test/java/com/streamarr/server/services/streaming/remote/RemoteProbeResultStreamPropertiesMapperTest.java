package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.ColorPrimaries;
import com.streamarr.server.domain.streaming.ColorRange;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionRpuHeader;
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
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Remote Probe Result Stream Properties Mapper Tests")
class RemoteProbeResultStreamPropertiesMapperTest {

  private static final int PROBE_VERSION_4 = 4;
  private static final int PROBE_VERSION_5 = 5;

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static final com.streamarr.transcode.v1.TransferCharacteristics PQ_TRANSFER =
      com.streamarr.transcode.v1.TransferCharacteristics.TRANSFER_CHARACTERISTICS_SMPTE2084;

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
    var codec = reported.name().split("_")[3].toLowerCase(Locale.ROOT);
    var video = hevc().setCodec(codec).setCodecProfile(reported).setCodecLevel(40);

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
    var video = hevc().setTransferCharacteristics(reported);
    video.getDynamicRangeBuilder().setCompatibleBase(signaledBase(reported));

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
                .setObservedRpu(com.streamarr.transcode.v1.Presence.PRESENCE_PRESENT)
                .setFirstRpuHeader(profile8RpuHeader())
                .setRpuStartsSample(com.streamarr.transcode.v1.Presence.PRESENCE_ABSENT));

    var stream = videoOf(version4(media -> media.setStreams(0, video)));

    assertThat(stream.videoProperties()).isEmpty();
    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::dolbyVision)
        .contains(new DolbyVision.Present(Optional.empty(), Presence.PRESENT));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should keep the first RPU header and the sample layout when the probe read RPUs")
  void shouldKeepTheFirstRpuHeaderAndTheSampleLayoutWhenTheProbeReadRpus() {
    var video = hevc().setFirstFramesRead(true);
    video
        .getDynamicRangeBuilder()
        .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
        .setDolbyVisionPresent(
            DolbyVisionPresent.newBuilder()
                .setObservedRpu(com.streamarr.transcode.v1.Presence.PRESENCE_PRESENT)
                .setFirstRpuHeader(profile8RpuHeader())
                .setRpuStartsSample(com.streamarr.transcode.v1.Presence.PRESENCE_PRESENT));

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::dolbyVision)
        .contains(
            new DolbyVision.Present(
                Optional.empty(),
                Presence.PRESENT,
                Optional.of(
                    DolbyVisionRpuHeader.builder()
                        .vdrRpuProfile(1)
                        .baseLayerFullRange(false)
                        .enhancementLayerSpatialResamplingFilter(false)
                        .disableResidual(true)
                        .vdrBitDepth(12)
                        .build()),
                Presence.PRESENT));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should keep a record-less profile 5 stream when its compatible base is none")
  void shouldKeepARecordLessProfile5StreamWhenItsCompatibleBaseIsNone() {
    var video = hevc().setFirstFramesRead(true);
    video
        .getDynamicRangeBuilder()
        .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_NONE)
        .setDolbyVisionPresent(
            DolbyVisionPresent.newBuilder()
                .setObservedRpu(com.streamarr.transcode.v1.Presence.PRESENCE_PRESENT)
                .setFirstRpuHeader(profile5RpuHeader()));

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::compatibleBase)
        .contains(com.streamarr.server.domain.streaming.CompatibleBase.NONE);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should keep a record-less AV1 profile 10.0 stream when its compatible base is none")
  void shouldKeepARecordLessAv1Profile10Point0StreamWhenItsCompatibleBaseIsNone() {
    var video = av1().setFirstFramesRead(true);
    video
        .getDynamicRangeBuilder()
        .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_NONE)
        .setDolbyVisionPresent(
            DolbyVisionPresent.newBuilder()
                .setObservedRpu(com.streamarr.transcode.v1.Presence.PRESENCE_PRESENT)
                .setFirstRpuHeader(profile5RpuHeader()));

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::compatibleBase)
        .contains(com.streamarr.server.domain.streaming.CompatibleBase.NONE);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest(name = "{0}")
  @MethodSource("declaredProfile10Bases")
  @DisplayName("Should keep a declared profile 10 stream when its record names a compatible base")
  void shouldKeepADeclaredProfile10StreamWhenItsRecordNamesACompatibleBase(
      String record,
      DolbyVisionConfiguration.Builder declared,
      com.streamarr.transcode.v1.TransferCharacteristics transfer,
      CompatibleBase base) {
    var video = av1().setTransferCharacteristics(transfer);
    video
        .getDynamicRangeBuilder()
        .setCompatibleBase(base)
        .setDolbyVisionPresent(DolbyVisionPresent.newBuilder().setDeclared(declared));

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::compatibleBase)
        .map(Enum::name)
        .contains(base.name().substring("COMPATIBLE_BASE_".length()));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static Stream<Arguments> declaredProfile10Bases() {
    return Stream.of(
        Arguments.of(
            "profile 10.1 with a PQ base",
            profile10Record().setBaseLayerCompatibilityId(1),
            PQ_TRANSFER,
            CompatibleBase.COMPATIBLE_BASE_PQ),
        Arguments.of(
            "profile 10.4 with an HLG base",
            profile10Record().setBaseLayerCompatibilityId(4),
            com.streamarr.transcode.v1.TransferCharacteristics
                .TRANSFER_CHARACTERISTICS_ARIB_STD_B67,
            CompatibleBase.COMPATIBLE_BASE_HLG),
        Arguments.of(
            "profile 10 without a compatibility identifier and with an SDR base",
            profile10Record(),
            com.streamarr.transcode.v1.TransferCharacteristics.TRANSFER_CHARACTERISTICS_BT709,
            CompatibleBase.COMPATIBLE_BASE_SDR));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("violations")
  @DisplayName("Should reject the result when it breaks the probe version 5 contract")
  void shouldRejectTheResultWhenItBreaksTheProbeVersion5Contract(
      String violation, UnaryOperator<ProbeStreamInfo.Builder> breakContract) {
    var report = report(version5(media -> media.setStreams(0, breakContract.apply(hevc()))));

    assertThatThrownBy(() -> mapper.map(report))
        .isInstanceOf(ProbeContractViolationException.class);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static Stream<Arguments> violations() {
    var present = com.streamarr.transcode.v1.Presence.PRESENCE_PRESENT;
    var absent = com.streamarr.transcode.v1.Presence.PRESENCE_ABSENT;
    return Stream.of(
        violation("a codec level without a codec profile", stream -> stream.setCodecLevel(41)),
        violation(
            "a codec tier without a codec profile",
            stream ->
                stream.setCodecTier(
                    com.streamarr.transcode.v1.VideoCodecTier.VIDEO_CODEC_TIER_MAIN)),
        violation(
            "a codec tier on an H.264 codec profile",
            stream ->
                stream
                    .setCodec("h264")
                    .setCodecProfile(
                        com.streamarr.transcode.v1.VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_HIGH)
                    .setCodecTier(com.streamarr.transcode.v1.VideoCodecTier.VIDEO_CODEC_TIER_MAIN)),
        violation(
            "an HEVC codec profile on an H.264 stream",
            stream ->
                stream
                    .setCodec("h264")
                    .setCodecProfile(
                        com.streamarr.transcode.v1.VideoCodecProfile
                            .VIDEO_CODEC_PROFILE_HEVC_MAIN_10)),
        violation(
            "an H.264 codec profile on an AV1 stream",
            stream ->
                stream
                    .setCodec("av1")
                    .setCodecProfile(
                        com.streamarr.transcode.v1.VideoCodecProfile
                            .VIDEO_CODEC_PROFILE_H264_HIGH)),
        violation(
            "an AV1 codec profile on an HEVC stream",
            stream ->
                stream.setCodecProfile(
                    com.streamarr.transcode.v1.VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN)),
        violation(
            "a codec profile on a VP9 stream",
            stream ->
                stream
                    .setCodec("vp9")
                    .setCodecProfile(
                        com.streamarr.transcode.v1.VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN)),
        violation(
            "a codec profile on a stream without a codec",
            stream ->
                stream
                    .clearCodec()
                    .setCodecProfile(
                        com.streamarr.transcode.v1.VideoCodecProfile
                            .VIDEO_CODEC_PROFILE_HEVC_MAIN)),
        violation(
            "a codec level of 0",
            stream ->
                stream
                    .setCodecProfile(
                        com.streamarr.transcode.v1.VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN)
                    .setCodecLevel(0)),
        violation(
            "a codec level beyond the integer range",
            stream ->
                stream
                    .setCodecProfile(
                        com.streamarr.transcode.v1.VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN)
                    .setCodecLevel(-1)),
        violation(
            "an omitted mastering display that the stream still lists",
            stream ->
                stream
                    .setMasteringDisplayOmitted(true)
                    .setMasteringDisplay(WorkerProbeResultFixture.masteringDisplayBuilder())),
        violation(
            "an omitted content light level that the stream still lists",
            stream ->
                stream
                    .setContentLightLevelOmitted(true)
                    .setContentLightLevel(ContentLightLevel.getDefaultInstance())),
        violation(
            "HDR10+ absent without reading frames",
            stream -> {
              stream.getDynamicRangeBuilder().setHdr10Plus(absent);
              return stream;
            }),
        violation(
            "Dolby Vision absent without reading frames",
            stream -> {
              stream
                  .getDynamicRangeBuilder()
                  .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance());
              return stream;
            }),
        violation(
            "an observed RPU absent without reading frames",
            stream -> {
              stream
                  .getDynamicRangeBuilder()
                  .setDolbyVisionPresent(DolbyVisionPresent.newBuilder().setObservedRpu(absent));
              return stream;
            }),
        violation(
            "a sample layout absent without reading frames",
            stream -> {
              stream
                  .setTransferCharacteristics(PQ_TRANSFER)
                  .getDynamicRangeBuilder()
                  .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
                  .setDolbyVisionPresent(
                      DolbyVisionPresent.newBuilder()
                          .setObservedRpu(present)
                          .setRpuStartsSample(absent));
              return stream;
            }),
        violation(
            "HDR10+ present without reading frames",
            stream -> {
              stream.getDynamicRangeBuilder().setHdr10Plus(present);
              return stream;
            }),
        violation(
            "an observed RPU present without reading frames",
            stream -> {
              stream
                  .setTransferCharacteristics(PQ_TRANSFER)
                  .getDynamicRangeBuilder()
                  .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
                  .setDolbyVisionPresent(DolbyVisionPresent.newBuilder().setObservedRpu(present));
              return stream;
            }),
        violation(
            "a first RPU header without an observed RPU",
            stream -> {
              stream
                  .setFirstFramesRead(true)
                  .getDynamicRangeBuilder()
                  .setDolbyVisionPresent(
                      DolbyVisionPresent.newBuilder()
                          .setObservedRpu(absent)
                          .setFirstRpuHeader(profile8RpuHeader()));
              return stream;
            }),
        violation(
            "a sample layout without an observed RPU",
            stream -> {
              stream
                  .setFirstFramesRead(true)
                  .getDynamicRangeBuilder()
                  .setDolbyVisionPresent(
                      DolbyVisionPresent.newBuilder()
                          .setObservedRpu(absent)
                          .setRpuStartsSample(absent));
              return stream;
            }),
        violation(
            "an RPU bit depth below 8",
            stream -> {
              stream
                  .setFirstFramesRead(true)
                  .setTransferCharacteristics(PQ_TRANSFER)
                  .getDynamicRangeBuilder()
                  .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
                  .setDolbyVisionPresent(
                      DolbyVisionPresent.newBuilder()
                          .setObservedRpu(present)
                          .setFirstRpuHeader(profile8RpuHeader().setVdrBitDepth(7)));
              return stream;
            }),
        violation(
            "an RPU profile beyond the integer range",
            stream -> {
              stream
                  .setFirstFramesRead(true)
                  .setTransferCharacteristics(PQ_TRANSFER)
                  .getDynamicRangeBuilder()
                  .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
                  .setDolbyVisionPresent(
                      DolbyVisionPresent.newBuilder()
                          .setObservedRpu(present)
                          .setFirstRpuHeader(profile8RpuHeader().setVdrRpuProfile(-1)));
              return stream;
            }),
        violation(
            "a record-less profile 5 stream with an SDR compatible base",
            stream -> {
              stream
                  .setFirstFramesRead(true)
                  .getDynamicRangeBuilder()
                  .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
                  .setDolbyVisionPresent(
                      DolbyVisionPresent.newBuilder()
                          .setObservedRpu(present)
                          .setFirstRpuHeader(profile5RpuHeader()));
              return stream;
            }),
        violation(
            "a profile 5 RPU header beside a record without a profile and with an SDR base",
            stream -> {
              stream
                  .setFirstFramesRead(true)
                  .getDynamicRangeBuilder()
                  .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
                  .setDolbyVisionPresent(
                      DolbyVisionPresent.newBuilder()
                          .setDeclared(profilelessRecord())
                          .setObservedRpu(present)
                          .setFirstRpuHeader(profile5RpuHeader()));
              return stream;
            }),
        violation(
            "an AV1 profile 10.0 RPU header beside a record without a profile and with an SDR base",
            stream -> {
              stream
                  .setCodec("av1")
                  .setFirstFramesRead(true)
                  .getDynamicRangeBuilder()
                  .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
                  .setDolbyVisionPresent(
                      DolbyVisionPresent.newBuilder()
                          .setDeclared(profilelessRecord())
                          .setObservedRpu(present)
                          .setFirstRpuHeader(profile5RpuHeader()));
              return stream;
            }),
        violation(
            "a declared profile 5 stream with an SDR compatible base",
            stream -> {
              stream
                  .getDynamicRangeBuilder()
                  .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
                  .setDolbyVisionPresent(
                      DolbyVisionPresent.newBuilder()
                          .setDeclared(
                              DolbyVisionConfiguration.newBuilder()
                                  .setProfile(5)
                                  .setBaseLayer(true)
                                  .setRpu(true)));
              return stream;
            }),
        violation(
            "a record-less AV1 profile 10.0 stream with an SDR compatible base",
            stream -> {
              stream
                  .setCodec("av1")
                  .setFirstFramesRead(true)
                  .getDynamicRangeBuilder()
                  .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
                  .setDolbyVisionPresent(
                      DolbyVisionPresent.newBuilder()
                          .setObservedRpu(present)
                          .setFirstRpuHeader(profile5RpuHeader()));
              return stream;
            }),
        violation(
            "a declared profile 10.0 stream with a PQ compatible base",
            stream -> {
              stream
                  .setCodec("av1")
                  .setTransferCharacteristics(PQ_TRANSFER)
                  .getDynamicRangeBuilder()
                  .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
                  .setDolbyVisionPresent(
                      DolbyVisionPresent.newBuilder()
                          .setDeclared(profile10Record().setBaseLayerCompatibilityId(0)));
              return stream;
            }),
        violation(
            "a sample layout on an AV1 stream",
            stream -> {
              stream
                  .setCodec("av1")
                  .setFirstFramesRead(true)
                  .setTransferCharacteristics(PQ_TRANSFER)
                  .getDynamicRangeBuilder()
                  .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
                  .setDolbyVisionPresent(
                      DolbyVisionPresent.newBuilder()
                          .setDeclared(profile10Record().setBaseLayerCompatibilityId(1))
                          .setObservedRpu(present)
                          .setRpuStartsSample(absent));
              return stream;
            }),
        violation(
            "HDR10+ absent on a VP9 stream",
            stream -> {
              stream
                  .setCodec("vp9")
                  .setFirstFramesRead(true)
                  .getDynamicRangeBuilder()
                  .setHdr10Plus(absent);
              return stream;
            }),
        violation(
            "HDR10+ present on a VP9 stream",
            stream -> {
              stream
                  .setCodec("vp9")
                  .setFirstFramesRead(true)
                  .getDynamicRangeBuilder()
                  .setHdr10Plus(present);
              return stream;
            }),
        violation(
            "Dolby Vision absent on an MPEG-2 stream",
            stream -> {
              stream
                  .setCodec("mpeg2video")
                  .setFirstFramesRead(true)
                  .getDynamicRangeBuilder()
                  .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance());
              return stream;
            }),
        violation(
            "an observed RPU absent on a VP9 stream",
            stream -> {
              stream
                  .setCodec("vp9")
                  .setFirstFramesRead(true)
                  .setTransferCharacteristics(PQ_TRANSFER)
                  .getDynamicRangeBuilder()
                  .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
                  .setDolbyVisionPresent(
                      DolbyVisionPresent.newBuilder()
                          .setDeclared(profile10Record().setBaseLayerCompatibilityId(1))
                          .setObservedRpu(absent));
              return stream;
            }),
        violation(
            "an observed RPU present on a stream without a codec",
            stream -> {
              stream
                  .clearCodec()
                  .setFirstFramesRead(true)
                  .setTransferCharacteristics(PQ_TRANSFER)
                  .getDynamicRangeBuilder()
                  .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
                  .setDolbyVisionPresent(DolbyVisionPresent.newBuilder().setObservedRpu(present));
              return stream;
            }));
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
    var video = hevc().setCodec("h264").setFirstFramesRead(true);
    video.getDynamicRangeBuilder().setHdr10Plus(hdr10Plus);

    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange())
        .map(VideoDynamicRange::hdr10Plus)
        .map(Enum::name)
        .contains(hdr10Plus.name().substring("PRESENCE_".length()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("visibleAbsences")
  @DisplayName("Should keep an absence when the probe can see the feature in the stream's codec")
  void shouldKeepAnAbsenceWhenTheProbeCanSeeTheFeatureInTheStreamsCodec(
      String absence, ProbeStreamInfo.Builder video, DolbyVision dolbyVision, Presence hdr10Plus) {
    var stream = videoOf(version5(media -> media.setStreams(0, video)));

    assertThat(stream.dynamicRange()).map(VideoDynamicRange::dolbyVision).contains(dolbyVision);
    assertThat(stream.dynamicRange()).map(VideoDynamicRange::hdr10Plus).contains(hdr10Plus);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static Stream<Arguments> visibleAbsences() {
    var absent = com.streamarr.transcode.v1.Presence.PRESENCE_ABSENT;
    var h264WithoutDolbyVision = hevc().setCodec("h264").setFirstFramesRead(true);
    h264WithoutDolbyVision
        .getDynamicRangeBuilder()
        .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance());
    var h264WithoutRpus = hevc().setCodec("h264").setFirstFramesRead(true);
    h264WithoutRpus
        .getDynamicRangeBuilder()
        .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
        .setDolbyVisionPresent(
            DolbyVisionPresent.newBuilder()
                .setDeclared(DolbyVisionConfiguration.newBuilder().setProfile(9))
                .setObservedRpu(absent));
    var hevcWithoutEither = hevc().setFirstFramesRead(true);
    hevcWithoutEither
        .getDynamicRangeBuilder()
        .setHdr10Plus(absent)
        .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance());
    var av1WithoutRpus = av1().setFirstFramesRead(true);
    av1WithoutRpus
        .setTransferCharacteristics(PQ_TRANSFER)
        .getDynamicRangeBuilder()
        .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
        .setDolbyVisionPresent(
            DolbyVisionPresent.newBuilder()
                .setDeclared(DolbyVisionConfiguration.newBuilder().setProfile(10))
                .setObservedRpu(absent));
    var av1WithoutEither = av1().setFirstFramesRead(true);
    av1WithoutEither
        .getDynamicRangeBuilder()
        .setHdr10Plus(absent)
        .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance());
    return Stream.of(
        Arguments.of(
            "Dolby Vision absent on an H.264 stream",
            h264WithoutDolbyVision,
            new DolbyVision.Absent(),
            Presence.UNSPECIFIED),
        Arguments.of(
            "an observed RPU absent on an H.264 stream",
            h264WithoutRpus,
            new DolbyVision.Present(
                Optional.of(
                    com.streamarr.server.domain.streaming.DolbyVisionConfiguration.builder()
                        .profile(OptionalInt.of(9))
                        .build()),
                Presence.ABSENT),
            Presence.UNSPECIFIED),
        Arguments.of(
            "HDR10+ and Dolby Vision absent on an HEVC stream",
            hevcWithoutEither,
            new DolbyVision.Absent(),
            Presence.ABSENT),
        Arguments.of(
            "an observed RPU absent on an AV1 stream",
            av1WithoutRpus,
            new DolbyVision.Present(
                Optional.of(
                    com.streamarr.server.domain.streaming.DolbyVisionConfiguration.builder()
                        .profile(OptionalInt.of(10))
                        .build()),
                Presence.ABSENT),
            Presence.UNSPECIFIED),
        Arguments.of(
            "HDR10+ and Dolby Vision absent on an AV1 stream",
            av1WithoutEither,
            new DolbyVision.Absent(),
            Presence.ABSENT));
  }

  @Test
  @DisplayName("Should reject the result when a stream other than the chosen one read frames")
  void shouldRejectTheResultWhenAStreamOtherThanTheChosenOneReadFrames() {
    var report =
        report(
            version5(
                media ->
                    media.addStreams(
                        ProbeStreamInfo.newBuilder()
                            .setIndex(1)
                            .setCodecType("video")
                            .setFirstFramesRead(true))));

    assertThatThrownBy(() -> mapper.map(report))
        .isInstanceOf(ProbeContractViolationException.class);
  }

  private static Arguments violation(
      String violation, UnaryOperator<ProbeStreamInfo.Builder> breakContract) {
    return Arguments.of(violation, breakContract);
  }

  private static ProbeStreamInfo.Builder hevc() {
    return ProbeStreamInfo.newBuilder().setIndex(0).setCodecType("video").setCodec("hevc");
  }

  private static ProbeStreamInfo.Builder av1() {
    return hevc().setCodec("av1");
  }

  // The compatible base that the signaled transfer decides when no Dolby Vision profile 5 or 10.0
  // overrides it.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static CompatibleBase signaledBase(
      com.streamarr.transcode.v1.TransferCharacteristics transfer) {
    return switch (transfer) {
      case TRANSFER_CHARACTERISTICS_SMPTE2084 -> CompatibleBase.COMPATIBLE_BASE_PQ;
      case TRANSFER_CHARACTERISTICS_ARIB_STD_B67 -> CompatibleBase.COMPATIBLE_BASE_HLG;
      default -> CompatibleBase.COMPATIBLE_BASE_UNSPECIFIED;
    };
  }

  private static DolbyVisionConfiguration.Builder profilelessRecord() {
    return DolbyVisionConfiguration.newBuilder().setBaseLayer(true).setRpu(true);
  }

  private static DolbyVisionConfiguration.Builder profile10Record() {
    return DolbyVisionConfiguration.newBuilder().setProfile(10).setBaseLayer(true).setRpu(true);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.DolbyVisionRpuHeader.Builder profile8RpuHeader() {
    return com.streamarr.transcode.v1.DolbyVisionRpuHeader.newBuilder()
        .setVdrRpuProfile(1)
        .setDisableResidual(true)
        .setVdrBitDepth(12);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.DolbyVisionRpuHeader.Builder profile5RpuHeader() {
    return com.streamarr.transcode.v1.DolbyVisionRpuHeader.newBuilder()
        .setVdrRpuProfile(0)
        .setBaseLayerFullRange(true)
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
