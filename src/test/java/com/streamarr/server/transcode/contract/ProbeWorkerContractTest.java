package com.streamarr.server.transcode.contract;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Duration;
import com.streamarr.transcode.v1.ChromaSubsampling;
import com.streamarr.transcode.v1.CompatibleBase;
import com.streamarr.transcode.v1.DolbyVisionAbsent;
import com.streamarr.transcode.v1.DolbyVisionConfiguration;
import com.streamarr.transcode.v1.DolbyVisionPresent;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.FieldOrder;
import com.streamarr.transcode.v1.Fraction;
import com.streamarr.transcode.v1.FrameRatePacketScan;
import com.streamarr.transcode.v1.FrameRateRuleInputs;
import com.streamarr.transcode.v1.FrameRateRuleResult;
import com.streamarr.transcode.v1.MatroskaStatistics;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.OutputFrameRateBasis;
import com.streamarr.transcode.v1.Presence;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeContainerInfo;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.v1.StartProbeCommand;
import com.streamarr.transcode.v1.VideoDynamicRange;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe Worker Contract Tests")
class ProbeWorkerContractTest {

  @Test
  @DisplayName("Should retain supported probe versions when serializing worker capabilities")
  void shouldRetainSupportedProbeVersionsWhenSerializingWorkerCapabilities() throws Exception {
    var capabilities =
        WorkerCapabilities.newBuilder().addProbeVersions(1).addProbeVersions(3).build();

    var decoded = WorkerCapabilities.parseFrom(capabilities.toByteArray());

    assertThat(decoded.getProbeVersionsList()).containsExactly(1, 3);
    assertThat(WorkerCapabilities.getDefaultInstance().getProbeVersionsList()).isEmpty();
  }

  @Test
  @DisplayName("Should retain probe attempt version and opaque source when sending a probe command")
  void shouldRetainProbeAttemptVersionAndOpaqueSourceWhenSendingProbeCommand() throws Exception {
    var attemptId = toProto(UUID.randomUUID());
    var source =
        MediaSourceRef.newBuilder()
            .setSourceNamespaceId(toProto(UUID.randomUUID()))
            .setRelativeKey("movies/feature%2Ffilm.mkv")
            .build();
    var worker =
        WorkerIdentity.newBuilder()
            .setWorkerId(toProto(UUID.randomUUID()))
            .setBootId(toProto(UUID.randomUUID()))
            .build();
    var command =
        EstablishWorkerSessionResponse.newBuilder()
            .setStartProbe(
                StartProbeCommand.newBuilder()
                    .setTarget(worker)
                    .setRequest(
                        ProbeRequest.newBuilder()
                            .setProbeAttemptId(attemptId)
                            .setProbeVersion(3)
                            .setSource(source)))
            .build();

    var decoded = EstablishWorkerSessionResponse.parseFrom(command.toByteArray()).getStartProbe();

    assertThat(decoded.getTarget()).isEqualTo(worker);
    assertThat(decoded.getRequest().getProbeAttemptId()).isEqualTo(attemptId);
    assertThat(decoded.getRequest().getProbeVersion()).isEqualTo(3);
    assertThat(decoded.getRequest().getSource()).isEqualTo(source);
  }

  @Test
  @DisplayName("Should preserve complete ordered stream properties when returning a probe result")
  void shouldPreserveCompleteOrderedStreamPropertiesWhenReturningProbeResult() throws Exception {
    var media =
        ProbeMediaInfo.newBuilder()
            .setContainer(
                ProbeContainerInfo.newBuilder()
                    .setFormat("matroska,webm")
                    .setDuration(Duration.newBuilder().setSeconds(125).setNanos(123456789))
                    .setBitrateBitsPerSecond(9_000_000))
            .addStreams(
                ProbeStreamInfo.newBuilder()
                    .setIndex(2)
                    .setCodecType("video")
                    .setCodec("h264")
                    .setWidth(1920)
                    .setHeight(1080)
                    .setFramerate(24000.0 / 1001)
                    .setBitrateBitsPerSecond(8_000_000))
            .addStreams(
                ProbeStreamInfo.newBuilder()
                    .setIndex(4)
                    .setCodecType("audio")
                    .setCodec("aac")
                    .setChannels(6)
                    .setLanguage("eng")
                    .setBitrateBitsPerSecond(512_000)
                    .setIsDefault(true))
            .addStreams(
                ProbeStreamInfo.newBuilder()
                    .setIndex(9)
                    .setCodecType("subtitle")
                    .setCodec("subrip")
                    .setLanguage("fra")
                    .setIsForced(true))
            .addStreams(ProbeStreamInfo.newBuilder().setIndex(11).setCodecType("unknown"))
            .build();
    var result =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setProbeVersion(3)
            .setMedia(media)
            .build();
    var event = EstablishWorkerSessionRequest.newBuilder().setProbeResult(result).build();

    var decoded = EstablishWorkerSessionRequest.parseFrom(event.toByteArray()).getProbeResult();

    assertThat(decoded).isEqualTo(result);
    assertThat(decoded.getMedia().getStreamsList())
        .extracting(ProbeStreamInfo::getIndex)
        .containsExactly(2, 4, 9, 11);
    var unknown = decoded.getMedia().getStreams(3);
    assertThat(unknown.hasCodec()).isFalse();
    assertThat(unknown.hasLanguage()).isFalse();
    assertThat(unknown.hasChannels()).isFalse();
    assertThat(unknown.hasBitrateBitsPerSecond()).isFalse();
    assertThat(unknown.hasWidth()).isFalse();
    assertThat(unknown.hasHeight()).isFalse();
    assertThat(unknown.hasFramerate()).isFalse();
    assertThat(unknown.hasDynamicRange()).isFalse();
    assertThat(unknown.hasBitDepth()).isFalse();
    assertThat(unknown.getChromaSubsampling())
        .isEqualTo(ChromaSubsampling.CHROMA_SUBSAMPLING_UNSPECIFIED);
    assertThat(unknown.hasRotationDegrees()).isFalse();
    assertThat(unknown.hasCodecString()).isFalse();
  }

  @Test
  @DisplayName(
      "Should report the facts of each video stream and the codec string of each audio stream when returning a probe version 4 result")
  void shouldReportVideoStreamFactsAndAudioCodecStringWhenReturningProbeVersion4Result()
      throws Exception {
    var dolbyVision81 =
        DolbyVisionConfiguration.newBuilder()
            .setProfile(8)
            .setLevel(6)
            .setBaseLayerCompatibilityId(1)
            .setBaseLayer(true)
            .setRpu(true)
            .build();
    var video =
        ProbeStreamInfo.newBuilder()
            .setIndex(0)
            .setCodecType("video")
            .setCodec("hevc")
            .setDynamicRange(
                VideoDynamicRange.newBuilder()
                    .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
                    .setHdr10Plus(Presence.PRESENCE_UNSPECIFIED)
                    .setDolbyVisionPresent(
                        DolbyVisionPresent.newBuilder()
                            .setDeclared(dolbyVision81)
                            .setObservedRpu(Presence.PRESENCE_UNSPECIFIED)))
            .setBitDepth(10)
            .setChromaSubsampling(ChromaSubsampling.CHROMA_SUBSAMPLING_420)
            .setRotationDegrees(-90)
            .setCodecString("hvc1.2.4.L153.B0")
            .build();
    var audio =
        ProbeStreamInfo.newBuilder()
            .setIndex(1)
            .setCodecType("audio")
            .setCodec("aac")
            .setCodecString("mp4a.40.2")
            .build();

    var decoded =
        ProbeAttemptResult.parseFrom(
                probeVersion4Result(ProbeMediaInfo.newBuilder().addStreams(video).addStreams(audio))
                    .toByteArray())
            .getMedia();

    assertThat(decoded.getStreams(0)).isEqualTo(video);
    var declared = decoded.getStreams(0).getDynamicRange().getDolbyVisionPresent().getDeclared();
    assertThat(declared.getProfile()).isEqualTo(8);
    assertThat(declared.getEnhancementLayer()).isFalse();
    assertThat(decoded.getStreams(0).getRotationDegrees()).isEqualTo(-90);
    assertThat(decoded.getStreams(1).getCodecString()).isEqualTo("mp4a.40.2");
    assertThat(decoded.getStreams(1).hasDynamicRange()).isFalse();
    assertThat(decoded.getStreams(1).hasBitDepth()).isFalse();
  }

  @Test
  @DisplayName(
      "Should tell unspecified Dolby Vision apart from absent Dolby Vision and from a sample entry without a record when decoding a dynamic range")
  void shouldTellUnspecifiedDolbyVisionApartFromAbsentAndFromSampleEntryWhenDecodingDynamicRange()
      throws Exception {
    var unspecified =
        VideoDynamicRange.newBuilder()
            .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
            .build();
    var absent =
        unspecified.toBuilder()
            .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance())
            .build();
    var sampleEntryOnly =
        VideoDynamicRange.newBuilder()
            .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_NONE)
            .setDolbyVisionPresent(DolbyVisionPresent.getDefaultInstance())
            .build();

    var decodedUnspecified = VideoDynamicRange.parseFrom(unspecified.toByteArray());
    var decodedAbsent = VideoDynamicRange.parseFrom(absent.toByteArray());
    var decodedSampleEntryOnly = VideoDynamicRange.parseFrom(sampleEntryOnly.toByteArray());

    assertThat(decodedUnspecified.getDolbyVisionCase())
        .isEqualTo(VideoDynamicRange.DolbyVisionCase.DOLBYVISION_NOT_SET);
    assertThat(decodedUnspecified.getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    assertThat(decodedAbsent.getDolbyVisionCase())
        .isEqualTo(VideoDynamicRange.DolbyVisionCase.DOLBY_VISION_ABSENT);
    assertThat(decodedSampleEntryOnly.getDolbyVisionCase())
        .isEqualTo(VideoDynamicRange.DolbyVisionCase.DOLBY_VISION_PRESENT);
    assertThat(decodedSampleEntryOnly.getDolbyVisionPresent().hasDeclared()).isFalse();
    assertThat(decodedSampleEntryOnly.getDolbyVisionPresent().getObservedRpu())
        .isEqualTo(Presence.PRESENCE_UNSPECIFIED);
  }

  @Test
  @DisplayName(
      "Should keep a base-layer compatibility identifier of zero apart from a missing one when decoding a Dolby Vision record")
  void shouldKeepBaseLayerCompatibilityIdOfZeroApartFromMissingOneWhenDecodingDolbyVisionRecord()
      throws Exception {
    var profile5 =
        DolbyVisionConfiguration.newBuilder()
            .setProfile(5)
            .setLevel(6)
            .setBaseLayerCompatibilityId(0)
            .build();
    var withoutIdentifier = DolbyVisionConfiguration.newBuilder().setProfile(5).build();

    var decodedProfile5 = DolbyVisionConfiguration.parseFrom(profile5.toByteArray());
    var decodedWithoutIdentifier =
        DolbyVisionConfiguration.parseFrom(withoutIdentifier.toByteArray());

    assertThat(decodedProfile5.hasBaseLayerCompatibilityId()).isTrue();
    assertThat(decodedProfile5.getBaseLayerCompatibilityId()).isZero();
    assertThat(decodedWithoutIdentifier.hasBaseLayerCompatibilityId()).isFalse();
    assertThat(decodedWithoutIdentifier.hasLevel()).isFalse();
  }

  @Test
  @DisplayName(
      "Should carry the output frame rate as an exact fraction with its basis and the rule's inputs when the rule scans packets")
  void shouldCarryOutputFrameRateAsExactFractionWithBasisAndInputsWhenRuleScansPackets()
      throws Exception {
    var inputs =
        FrameRateRuleInputs.newBuilder()
            .setBaseRate(fraction(60, 1))
            .setAverageRate(fraction(5_994_000, 100_349))
            .setFieldOrder(FieldOrder.FIELD_ORDER_PROGRESSIVE)
            .setTimeBase(fraction(1, 600))
            .setMatroskaStatistics(
                MatroskaStatistics.newBuilder()
                    .setNumberOfFrames(359_640)
                    .setDuration(Duration.newBuilder().setSeconds(6_000)))
            .setPacketScan(
                FrameRatePacketScan.newBuilder()
                    .setMotionRate(59.94)
                    .setScannedFrameCount(1_798)
                    .setScannedDuration(Duration.newBuilder().setSeconds(30)))
            .build();
    var rule =
        FrameRateRuleResult.newBuilder()
            .setOutputFrameRate(fraction(60_000, 1_001))
            .setBasis(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_PACKET_SCAN)
            .setInputs(inputs)
            .build();

    var decoded =
        ProbeAttemptResult.parseFrom(
                probeVersion4Result(
                        ProbeMediaInfo.newBuilder()
                            .setChosenVideoStreamIndex(0)
                            .setFrameRateRule(rule))
                    .toByteArray())
            .getMedia()
            .getFrameRateRule();

    assertThat(decoded).isEqualTo(rule);
    assertThat(decoded.getOutputFrameRate().getNumerator()).isEqualTo(60_000);
    assertThat(decoded.getOutputFrameRate().getDenominator()).isEqualTo(1_001);
    assertThat(decoded.getInputs().getPacketScan().getScannedFrameCount()).isEqualTo(1_798);
  }

  @Test
  @DisplayName(
      "Should leave the output frame rate absent when the frame-rate rule refuses a stream without usable rates")
  void shouldLeaveOutputFrameRateAbsentWhenFrameRateRuleRefusesStreamWithoutUsableRates()
      throws Exception {
    var refused =
        FrameRateRuleResult.newBuilder()
            .setBasis(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_REFUSED)
            .setInputs(
                FrameRateRuleInputs.newBuilder()
                    .setTimeBase(fraction(1, 1_000))
                    .setPacketScan(FrameRatePacketScan.newBuilder().setScannedFrameCount(1)))
            .build();

    var decoded = FrameRateRuleResult.parseFrom(refused.toByteArray());

    assertThat(decoded.getBasis()).isEqualTo(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_REFUSED);
    assertThat(decoded.hasOutputFrameRate()).isFalse();
    assertThat(decoded.getInputs().hasBaseRate()).isFalse();
    assertThat(decoded.getInputs().hasAverageRate()).isFalse();
    assertThat(decoded.getInputs().hasMatroskaStatistics()).isFalse();
    assertThat(decoded.getInputs().getPacketScan().hasMotionRate()).isFalse();
  }

  @Test
  @DisplayName(
      "Should name the chosen video stream when cover art comes before the video in a probe version 4 result")
  void shouldNameChosenVideoStreamWhenCoverArtComesBeforeVideoInProbeVersion4Result()
      throws Exception {
    var coverArt = ProbeStreamInfo.newBuilder().setIndex(0).setCodecType("video").setCodec("mjpeg");
    var film = ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("video").setCodec("h264");

    var decoded =
        ProbeAttemptResult.parseFrom(
                probeVersion4Result(
                        ProbeMediaInfo.newBuilder()
                            .addStreams(coverArt)
                            .addStreams(film)
                            .setChosenVideoStreamIndex(1))
                    .toByteArray())
            .getMedia();

    assertThat(decoded.hasChosenVideoStreamIndex()).isTrue();
    assertThat(decoded.getChosenVideoStreamIndex()).isEqualTo(1);
    assertThat(ProbeMediaInfo.getDefaultInstance().hasChosenVideoStreamIndex()).isFalse();
    assertThat(ProbeMediaInfo.getDefaultInstance().hasFrameRateRule()).isFalse();
  }

  @Test
  @DisplayName("Should preserve a typed media failure when returning an unsuccessful probe")
  void shouldPreserveTypedMediaFailureWhenReturningUnsuccessfulProbe() throws Exception {
    var result =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setProbeVersion(1)
            .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
            .build();

    var decoded = ProbeAttemptResult.parseFrom(result.toByteArray());

    assertThat(decoded).isEqualTo(result);
    assertThat(decoded.hasMedia()).isFalse();
    assertThat(decoded.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
  }

  private ProbeAttemptResult probeVersion4Result(ProbeMediaInfo.Builder media) {
    return ProbeAttemptResult.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(4)
        .setMedia(media)
        .build();
  }

  private Fraction fraction(int numerator, int denominator) {
    return Fraction.newBuilder().setNumerator(numerator).setDenominator(denominator).build();
  }
}
