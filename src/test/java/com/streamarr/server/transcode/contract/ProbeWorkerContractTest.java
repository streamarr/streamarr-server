package com.streamarr.server.transcode.contract;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Duration;
import com.streamarr.transcode.v1.ChromaSubsampling;
import com.streamarr.transcode.v1.ChromaticityCoordinates;
import com.streamarr.transcode.v1.CompatibleBase;
import com.streamarr.transcode.v1.ContainerCropping;
import com.streamarr.transcode.v1.ContentLightLevel;
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
import com.streamarr.transcode.v1.MasteringDisplayColorVolume;
import com.streamarr.transcode.v1.MatroskaStatistics;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.OutputFrameRateBasis;
import com.streamarr.transcode.v1.Presence;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeChapter;
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
    assertThat(unknown.hasEndTime()).isFalse();
    assertThat(unknown.hasSampleRateHz()).isFalse();
    assertThat(unknown.hasAudioProfile()).isFalse();
    assertThat(unknown.hasTitle()).isFalse();
    assertThat(unknown.hasHandlerName()).isFalse();
    assertThat(unknown.hasContainerCropping()).isFalse();
    assertThat(decoded.getMedia().getContainer().getChaptersList()).isEmpty();
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
  @DisplayName(
      "Should carry the end time of each audio and video stream when the container header states it in a probe version 4 result")
  void shouldCarryEndTimeOfEachAudioAndVideoStreamWhenContainerHeaderStatesItInProbeVersion4Result()
      throws Exception {
    var videoEnd = Duration.newBuilder().setSeconds(6_000).setNanos(48_000_000).build();
    var earlyAudioEnd = Duration.newBuilder().setSeconds(5_964).setNanos(500_000_000).build();
    var video =
        ProbeStreamInfo.newBuilder()
            .setIndex(0)
            .setCodecType("video")
            .setCodec("h264")
            .setEndTime(videoEnd);
    var audio =
        ProbeStreamInfo.newBuilder()
            .setIndex(1)
            .setCodecType("audio")
            .setCodec("ac3")
            .setEndTime(earlyAudioEnd);
    var subtitle = ProbeStreamInfo.newBuilder().setIndex(2).setCodecType("subtitle");

    var decoded =
        ProbeAttemptResult.parseFrom(
                probeVersion4Result(
                        ProbeMediaInfo.newBuilder()
                            .addStreams(video)
                            .addStreams(audio)
                            .addStreams(subtitle))
                    .toByteArray())
            .getMedia();

    assertThat(decoded.getStreams(0).getEndTime()).isEqualTo(videoEnd);
    assertThat(decoded.getStreams(1).getEndTime()).isEqualTo(earlyAudioEnd);
    assertThat(decoded.getStreams(2).hasEndTime()).isFalse();
  }

  @Test
  @DisplayName(
      "Should tell a stated end time of zero apart from a missing end time when decoding a stream")
  void shouldTellStatedEndTimeOfZeroApartFromMissingEndTimeWhenDecodingStream() throws Exception {
    var statedZero =
        ProbeStreamInfo.newBuilder()
            .setIndex(1)
            .setCodecType("audio")
            .setEndTime(Duration.getDefaultInstance())
            .build();
    var unstated = ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("audio").build();

    var decodedStatedZero = ProbeStreamInfo.parseFrom(statedZero.toByteArray());
    var decodedUnstated = ProbeStreamInfo.parseFrom(unstated.toByteArray());

    assertThat(decodedStatedZero.hasEndTime()).isTrue();
    assertThat(decodedStatedZero.getEndTime()).isEqualTo(Duration.getDefaultInstance());
    assertThat(decodedUnstated.hasEndTime()).isFalse();
  }

  @Test
  @DisplayName(
      "Should carry the sample rate and profile name of each audio stream when returning a probe version 4 result")
  void shouldCarrySampleRateAndProfileNameOfEachAudioStreamWhenReturningProbeVersion4Result()
      throws Exception {
    var atmos =
        ProbeStreamInfo.newBuilder()
            .setIndex(1)
            .setCodecType("audio")
            .setCodec("eac3")
            .setChannels(6)
            .setSampleRateHz(48_000)
            .setAudioProfile("Dolby Digital Plus + Dolby Atmos")
            .build();
    var lowSampleRateMp3 =
        ProbeStreamInfo.newBuilder()
            .setIndex(2)
            .setCodecType("audio")
            .setCodec("mp3")
            .setSampleRateHz(11_025)
            .build();

    var decoded =
        ProbeAttemptResult.parseFrom(
                probeVersion4Result(
                        ProbeMediaInfo.newBuilder().addStreams(atmos).addStreams(lowSampleRateMp3))
                    .toByteArray())
            .getMedia();

    assertThat(decoded.getStreams(0)).isEqualTo(atmos);
    assertThat(decoded.getStreams(0).getSampleRateHz()).isEqualTo(48_000);
    assertThat(decoded.getStreams(0).getAudioProfile())
        .isEqualTo("Dolby Digital Plus + Dolby Atmos");
    assertThat(decoded.getStreams(1).getSampleRateHz()).isEqualTo(11_025);
    assertThat(decoded.getStreams(1).hasAudioProfile()).isFalse();
  }

  @Test
  @DisplayName(
      "Should tell an unreported sample rate and profile name apart from reported ones when decoding an audio stream")
  void shouldTellUnreportedSampleRateAndProfileNameApartFromReportedOnesWhenDecodingAudioStream()
      throws Exception {
    var reportedZeroAndEmpty =
        ProbeStreamInfo.newBuilder()
            .setIndex(1)
            .setCodecType("audio")
            .setSampleRateHz(0)
            .setAudioProfile("")
            .build();
    var unreported = ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("audio").build();

    var decodedReported = ProbeStreamInfo.parseFrom(reportedZeroAndEmpty.toByteArray());
    var decodedUnreported = ProbeStreamInfo.parseFrom(unreported.toByteArray());

    assertThat(decodedReported.hasSampleRateHz()).isTrue();
    assertThat(decodedReported.getSampleRateHz()).isZero();
    assertThat(decodedReported.hasAudioProfile()).isTrue();
    assertThat(decodedReported.getAudioProfile()).isEmpty();
    assertThat(decodedUnreported.hasSampleRateHz()).isFalse();
    assertThat(decodedUnreported.hasAudioProfile()).isFalse();
  }

  @Test
  @DisplayName(
      "Should carry the title of each audio and subtitle stream when returning a probe version 4 result")
  void shouldCarryTitleOfEachAudioAndSubtitleStreamWhenReturningProbeVersion4Result()
      throws Exception {
    var video = ProbeStreamInfo.newBuilder().setIndex(0).setCodecType("video").build();
    var commentary =
        ProbeStreamInfo.newBuilder()
            .setIndex(1)
            .setCodecType("audio")
            .setCodec("aac")
            .setLanguage("eng")
            .setTitle("Director's Commentary")
            .build();
    var sdh =
        ProbeStreamInfo.newBuilder()
            .setIndex(2)
            .setCodecType("subtitle")
            .setCodec("mov_text")
            .setLanguage("fra")
            .setTitle("Français (Québec) SDH")
            .build();
    var untitled =
        ProbeStreamInfo.newBuilder()
            .setIndex(3)
            .setCodecType("subtitle")
            .setCodec("subrip")
            .setLanguage("eng")
            .build();

    var decoded =
        ProbeAttemptResult.parseFrom(
                probeVersion4Result(
                        ProbeMediaInfo.newBuilder()
                            .addStreams(video)
                            .addStreams(commentary)
                            .addStreams(sdh)
                            .addStreams(untitled))
                    .toByteArray())
            .getMedia();

    assertThat(decoded.getStreamsList()).containsExactly(video, commentary, sdh, untitled);
    assertThat(decoded.getStreams(0).hasTitle()).isFalse();
    assertThat(decoded.getStreams(1).getTitle()).isEqualTo("Director's Commentary");
    assertThat(decoded.getStreams(2).getTitle()).isEqualTo("Français (Québec) SDH");
    assertThat(decoded.getStreams(3).hasTitle()).isFalse();
  }

  @Test
  @DisplayName(
      "Should tell an empty title apart from a missing title when decoding a subtitle stream")
  void shouldTellEmptyTitleApartFromMissingTitleWhenDecodingSubtitleStream() throws Exception {
    var emptyTitle =
        ProbeStreamInfo.newBuilder().setIndex(2).setCodecType("subtitle").setTitle("").build();
    var noTitle = ProbeStreamInfo.newBuilder().setIndex(2).setCodecType("subtitle").build();

    var decodedEmpty = ProbeStreamInfo.parseFrom(emptyTitle.toByteArray());
    var decodedMissing = ProbeStreamInfo.parseFrom(noTitle.toByteArray());

    assertThat(decodedEmpty.hasTitle()).isTrue();
    assertThat(decodedEmpty.getTitle()).isEmpty();
    assertThat(decodedMissing.hasTitle()).isFalse();
  }

  @Test
  @DisplayName(
      "Should carry the raw handler name of each audio and subtitle stream beside its title when returning a probe version 4 result")
  void
      shouldCarryRawHandlerNameOfEachAudioAndSubtitleStreamBesideTitleWhenReturningProbeVersion4Result()
          throws Exception {
    var commentary =
        ProbeStreamInfo.newBuilder()
            .setIndex(1)
            .setCodecType("audio")
            .setTitle("Director's Commentary")
            .setHandlerName("SoundHandler")
            .build();
    var namedOnlyByHandler =
        ProbeStreamInfo.newBuilder()
            .setIndex(2)
            .setCodecType("subtitle")
            .setHandlerName("English SDH")
            .build();
    var producerName =
        ProbeStreamInfo.newBuilder()
            .setIndex(3)
            .setCodecType("audio")
            .setHandlerName("ISO Media file produced by Google Inc.")
            .build();

    var decoded =
        ProbeAttemptResult.parseFrom(
                probeVersion4Result(
                        ProbeMediaInfo.newBuilder()
                            .addStreams(commentary)
                            .addStreams(namedOnlyByHandler)
                            .addStreams(producerName))
                    .toByteArray())
            .getMedia();

    assertThat(decoded.getStreamsList())
        .containsExactly(commentary, namedOnlyByHandler, producerName);
    assertThat(decoded.getStreams(0).getTitle()).isEqualTo("Director's Commentary");
    assertThat(decoded.getStreams(0).getHandlerName()).isEqualTo("SoundHandler");
    assertThat(decoded.getStreams(1).hasTitle()).isFalse();
    assertThat(decoded.getStreams(1).getHandlerName()).isEqualTo("English SDH");
    assertThat(decoded.getStreams(2).hasTitle()).isFalse();
    assertThat(decoded.getStreams(2).getHandlerName())
        .isEqualTo("ISO Media file produced by Google Inc.");
  }

  @Test
  @DisplayName(
      "Should tell an empty handler name apart from a missing handler name when decoding an audio stream")
  void shouldTellEmptyHandlerNameApartFromMissingHandlerNameWhenDecodingAudioStream()
      throws Exception {
    var emptyHandlerName =
        ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("audio").setHandlerName("").build();
    var noHandlerName = ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("audio").build();

    var decodedEmpty = ProbeStreamInfo.parseFrom(emptyHandlerName.toByteArray());
    var decodedMissing = ProbeStreamInfo.parseFrom(noHandlerName.toByteArray());

    assertThat(decodedEmpty.hasHandlerName()).isTrue();
    assertThat(decodedEmpty.getHandlerName()).isEmpty();
    assertThat(decodedMissing.hasHandlerName()).isFalse();
  }

  @Test
  @DisplayName(
      "Should tell stream titles omitted for their size apart from streams without titles when decoding a probe version 4 result")
  void
      shouldTellStreamTitlesOmittedForSizeApartFromStreamsWithoutTitlesWhenDecodingProbeVersion4Result()
          throws Exception {
    var subtitle = ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("subtitle").build();
    var omitted =
        probeVersion4Result(
            ProbeMediaInfo.newBuilder().addStreams(subtitle).setStreamTitlesOmitted(true));
    var withoutTitles = probeVersion4Result(ProbeMediaInfo.newBuilder().addStreams(subtitle));

    var decodedOmitted = ProbeAttemptResult.parseFrom(omitted.toByteArray()).getMedia();
    var decodedWithoutTitles = ProbeAttemptResult.parseFrom(withoutTitles.toByteArray()).getMedia();

    assertThat(decodedOmitted.getStreams(0).hasTitle()).isFalse();
    assertThat(decodedOmitted.getStreams(0).hasHandlerName()).isFalse();
    assertThat(decodedOmitted.getStreamTitlesOmitted()).isTrue();
    assertThat(decodedWithoutTitles.getStreams(0).hasTitle()).isFalse();
    assertThat(decodedWithoutTitles.getStreams(0).hasHandlerName()).isFalse();
    assertThat(decodedWithoutTitles.getStreamTitlesOmitted()).isFalse();
  }

  @Test
  @DisplayName(
      "Should carry the container cropping of a video stream beside its size before cropping when returning a probe version 4 result")
  void
      shouldCarryContainerCroppingOfVideoStreamBesideSizeBeforeCroppingWhenReturningProbeVersion4Result()
          throws Exception {
    var letterboxCrop = ContainerCropping.newBuilder().setTop(140).setBottom(140).build();
    var cropped =
        ProbeStreamInfo.newBuilder()
            .setIndex(0)
            .setCodecType("video")
            .setCodec("hevc")
            .setWidth(3840)
            .setHeight(2160)
            .setContainerCropping(letterboxCrop)
            .build();
    var uncropped =
        ProbeStreamInfo.newBuilder()
            .setIndex(1)
            .setCodecType("video")
            .setCodec("h264")
            .setWidth(1920)
            .setHeight(1080)
            .build();

    var decoded =
        ProbeAttemptResult.parseFrom(
                probeVersion4Result(
                        ProbeMediaInfo.newBuilder().addStreams(cropped).addStreams(uncropped))
                    .toByteArray())
            .getMedia();

    assertThat(decoded.getStreams(0)).isEqualTo(cropped);
    assertThat(decoded.getStreams(0).getHeight()).isEqualTo(2160);
    var decodedCrop = decoded.getStreams(0).getContainerCropping();
    assertThat(decodedCrop.getTop()).isEqualTo(140);
    assertThat(decodedCrop.getBottom()).isEqualTo(140);
    assertThat(decodedCrop.getLeft()).isZero();
    assertThat(decodedCrop.getRight()).isZero();
    assertThat(decoded.getStreams(1).hasContainerCropping()).isFalse();
  }

  @Test
  @DisplayName(
      "Should tell a container crop of zero on every edge apart from a missing crop when decoding a video stream")
  void shouldTellContainerCropOfZeroOnEveryEdgeApartFromMissingCropWhenDecodingVideoStream()
      throws Exception {
    var zeroCrop =
        ProbeStreamInfo.newBuilder()
            .setIndex(0)
            .setCodecType("video")
            .setContainerCropping(ContainerCropping.getDefaultInstance())
            .build();
    var noCrop = ProbeStreamInfo.newBuilder().setIndex(0).setCodecType("video").build();

    var decodedZeroCrop = ProbeStreamInfo.parseFrom(zeroCrop.toByteArray());
    var decodedNoCrop = ProbeStreamInfo.parseFrom(noCrop.toByteArray());

    assertThat(decodedZeroCrop.hasContainerCropping()).isTrue();
    assertThat(decodedZeroCrop.getContainerCropping())
        .isEqualTo(ContainerCropping.getDefaultInstance());
    assertThat(decodedNoCrop.hasContainerCropping()).isFalse();
  }

  @Test
  @DisplayName(
      "Should carry the container's chapters in their order with their times and titles when returning a probe version 4 result")
  void shouldCarryContainerChaptersInOrderWithTimesAndTitlesWhenReturningProbeVersion4Result()
      throws Exception {
    var beforeContainerStart =
        ProbeChapter.newBuilder()
            .setStartTime(Duration.newBuilder().setNanos(-46_000_000))
            .setEndTime(Duration.newBuilder().setSeconds(95).setNanos(954_000_000))
            .setTitle("Opening")
            .build();
    var untitled =
        ProbeChapter.newBuilder()
            .setStartTime(Duration.newBuilder().setSeconds(95).setNanos(954_000_000))
            .setEndTime(Duration.newBuilder().setSeconds(1_200))
            .build();
    var container =
        ProbeContainerInfo.newBuilder()
            .setFormat("matroska,webm")
            .addChapters(beforeContainerStart)
            .addChapters(untitled);

    var decoded =
        ProbeAttemptResult.parseFrom(
                probeVersion4Result(ProbeMediaInfo.newBuilder().setContainer(container))
                    .toByteArray())
            .getMedia()
            .getContainer();

    assertThat(decoded.getChaptersList()).containsExactly(beforeContainerStart, untitled);
    assertThat(decoded.getChapters(0).getStartTime().getNanos()).isEqualTo(-46_000_000);
    assertThat(decoded.getChapters(0).getTitle()).isEqualTo("Opening");
    assertThat(decoded.getChapters(1).hasTitle()).isFalse();
  }

  @Test
  @DisplayName(
      "Should tell a chapter that starts at zero apart from a chapter without a start time when decoding a chapter")
  void shouldTellChapterThatStartsAtZeroApartFromChapterWithoutStartTimeWhenDecodingChapter()
      throws Exception {
    var startsAtZero =
        ProbeChapter.newBuilder().setStartTime(Duration.getDefaultInstance()).build();
    var withoutTimes = ProbeChapter.getDefaultInstance();

    var decodedStartsAtZero = ProbeChapter.parseFrom(startsAtZero.toByteArray());
    var decodedWithoutTimes = ProbeChapter.parseFrom(withoutTimes.toByteArray());

    assertThat(decodedStartsAtZero.hasStartTime()).isTrue();
    assertThat(decodedStartsAtZero.getStartTime()).isEqualTo(Duration.getDefaultInstance());
    assertThat(decodedWithoutTimes.hasStartTime()).isFalse();
    assertThat(decodedWithoutTimes.hasEndTime()).isFalse();
    assertThat(decodedWithoutTimes.hasTitle()).isFalse();
  }

  @Test
  @DisplayName(
      "Should tell chapters omitted for their size apart from a container without chapters when decoding a probe version 4 result")
  void
      shouldTellChaptersOmittedForSizeApartFromContainerWithoutChaptersWhenDecodingProbeVersion4Result()
          throws Exception {
    var omitted =
        probeVersion4Result(
            ProbeMediaInfo.newBuilder()
                .setContainer(ProbeContainerInfo.newBuilder().setChaptersOmitted(true)));
    var withoutChapters =
        probeVersion4Result(
            ProbeMediaInfo.newBuilder().setContainer(ProbeContainerInfo.newBuilder()));

    var decodedOmitted =
        ProbeAttemptResult.parseFrom(omitted.toByteArray()).getMedia().getContainer();
    var decodedWithoutChapters =
        ProbeAttemptResult.parseFrom(withoutChapters.toByteArray()).getMedia().getContainer();

    assertThat(decodedOmitted.getChaptersList()).isEmpty();
    assertThat(decodedOmitted.getChaptersOmitted()).isTrue();
    assertThat(decodedWithoutChapters.getChaptersList()).isEmpty();
    assertThat(decodedWithoutChapters.getChaptersOmitted()).isFalse();
  }

  @Test
  @DisplayName(
      "Should carry the HDR10 static metadata of a video stream in the units of its SEI messages when returning a probe version 4 result")
  void shouldCarryHdr10StaticMetadataInSeiUnitsWhenReturningProbeVersion4Result() throws Exception {
    var bt2020Mastering =
        MasteringDisplayColorVolume.newBuilder()
            .setRed(chromaticity(35_400, 14_600))
            .setGreen(chromaticity(8_500, 39_850))
            .setBlue(chromaticity(6_550, 2_300))
            .setWhitePoint(chromaticity(15_635, 16_450))
            .setMaxLuminance(40_000_000)
            .setMinLuminance(50)
            .build();
    var lightLevel =
        ContentLightLevel.newBuilder()
            .setMaxContentLightLevel(1_000)
            .setMaxFrameAverageLightLevel(400)
            .build();
    var hdr10 =
        ProbeStreamInfo.newBuilder()
            .setIndex(0)
            .setCodecType("video")
            .setCodec("hevc")
            .setMasteringDisplay(bt2020Mastering)
            .setContentLightLevel(lightLevel)
            .build();
    var sdr = ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("video").build();

    var decoded =
        ProbeAttemptResult.parseFrom(
                probeVersion4Result(ProbeMediaInfo.newBuilder().addStreams(hdr10).addStreams(sdr))
                    .toByteArray())
            .getMedia();

    assertThat(decoded.getStreams(0)).isEqualTo(hdr10);
    var mastering = decoded.getStreams(0).getMasteringDisplay();
    assertThat(mastering.getRed()).isEqualTo(chromaticity(35_400, 14_600));
    assertThat(mastering.getWhitePoint()).isEqualTo(chromaticity(15_635, 16_450));
    assertThat(mastering.getMaxLuminance()).isEqualTo(40_000_000);
    assertThat(mastering.getMinLuminance()).isEqualTo(50);
    assertThat(decoded.getStreams(0).getContentLightLevel().getMaxContentLightLevel())
        .isEqualTo(1_000);
    assertThat(decoded.getStreams(0).getContentLightLevel().getMaxFrameAverageLightLevel())
        .isEqualTo(400);
    assertThat(decoded.getStreams(1).hasMasteringDisplay()).isFalse();
    assertThat(decoded.getStreams(1).hasContentLightLevel()).isFalse();
  }

  @Test
  @DisplayName(
      "Should tell declared light levels of zero apart from a missing declaration when decoding a video stream")
  void shouldTellDeclaredLightLevelsOfZeroApartFromMissingDeclarationWhenDecodingVideoStream()
      throws Exception {
    var zeroLightLevel =
        ProbeStreamInfo.newBuilder()
            .setIndex(0)
            .setCodecType("video")
            .setContentLightLevel(ContentLightLevel.getDefaultInstance())
            .build();
    var noLightLevel = ProbeStreamInfo.newBuilder().setIndex(0).setCodecType("video").build();

    var decodedZero = ProbeStreamInfo.parseFrom(zeroLightLevel.toByteArray());
    var decodedNone = ProbeStreamInfo.parseFrom(noLightLevel.toByteArray());

    assertThat(decodedZero.hasContentLightLevel()).isTrue();
    assertThat(decodedZero.getContentLightLevel().getMaxContentLightLevel()).isZero();
    assertThat(decodedZero.getContentLightLevel().getMaxFrameAverageLightLevel()).isZero();
    assertThat(decodedNone.hasContentLightLevel()).isFalse();
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

  private ChromaticityCoordinates chromaticity(int x, int y) {
    return ChromaticityCoordinates.newBuilder().setX(x).setY(y).build();
  }

  private Fraction fraction(int numerator, int denominator) {
    return Fraction.newBuilder().setNumerator(numerator).setDenominator(denominator).build();
  }
}
