package com.streamarr.server.transcode.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.google.protobuf.Any;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.Type;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.streamarr.transcode.v1.AudioChannelLayout;
import com.streamarr.transcode.v1.AudioDecision;
import com.streamarr.transcode.v1.AudioMode;
import com.streamarr.transcode.v1.BitrateScanChunk;
import com.streamarr.transcode.v1.BitrateScanChunkResult;
import com.streamarr.transcode.v1.BitrateScanChunkStream;
import com.streamarr.transcode.v1.BitrateScanChunkStreams;
import com.streamarr.transcode.v1.BitrateScanCopyChangesPackets;
import com.streamarr.transcode.v1.CompatibleBase;
import com.streamarr.transcode.v1.Deinterlace;
import com.streamarr.transcode.v1.DeinterlaceOutput;
import com.streamarr.transcode.v1.DolbyVisionAbsent;
import com.streamarr.transcode.v1.EncoderKind;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.FieldOrder;
import com.streamarr.transcode.v1.Fraction;
import com.streamarr.transcode.v1.HeldBackJobAttempt;
import com.streamarr.transcode.v1.HoldBackCommand;
import com.streamarr.transcode.v1.JobAttemptCompleted;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.JobAttemptProgress;
import com.streamarr.transcode.v1.JobAttemptStarted;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.Presence;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbePriority;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.v1.SegmentContentType;
import com.streamarr.transcode.v1.SegmentUploadLimitReached;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.SourceEndedEarly;
import com.streamarr.transcode.v1.SourceSnapshot;
import com.streamarr.transcode.v1.SourceStreamEnd;
import com.streamarr.transcode.v1.SourceStreamWithoutSamples;
import com.streamarr.transcode.v1.StartVariantCommand;
import com.streamarr.transcode.v1.TranscodeDecision;
import com.streamarr.transcode.v1.TranscodeExecution;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.VideoDecision;
import com.streamarr.transcode.v1.VideoDynamicRange;
import com.streamarr.transcode.v1.VideoEncoderBackend;
import com.streamarr.transcode.v1.VideoEncodersChanged;
import com.streamarr.transcode.v1.VideoMode;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.v1.WorkerVerifiedVideoEncoder;
import com.streamarr.transcode.v1.YieldToSeekCommand;
import io.grpc.MethodDescriptor.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Transcode Worker Contract Tests")
class TranscodeWorkerContractTest {
  @Test
  @DisplayName(
      "Should expose only the worker session, segment upload and probe report calls when"
          + " validating the protocol")
  void shouldExposeOnlyTheWorkerSessionSegmentUploadAndProbeReportCallsWhenValidatingProtocol() {
    var service = TranscodeWorkerServiceGrpc.getServiceDescriptor();

    assertThat(service.getMethods()).hasSize(3);
    assertThat(TranscodeWorkerServiceGrpc.getEstablishWorkerSessionMethod().getType())
        .isEqualTo(MethodType.BIDI_STREAMING);
    assertThat(TranscodeWorkerServiceGrpc.getUploadSegmentMethod().getType())
        .isEqualTo(MethodType.CLIENT_STREAMING);
    assertThat(TranscodeWorkerServiceGrpc.getReportProbeAttemptMethod().getType())
        .isEqualTo(MethodType.CLIENT_STREAMING);
  }

  @Test
  @DisplayName(
      "Should carry self-reported worker identity and distinct job attempt identity when validating the protocol")
  void shouldCarrySelfReportedWorkerIdentityAndDistinctJobAttemptIdentityWhenValidatingProtocol()
      throws Exception {
    var sourceNamespaceId = UUID.randomUUID();
    var registration =
        EstablishWorkerSessionRequest.newBuilder()
            .setRegistration(
                WorkerRegistration.newBuilder()
                    .setWorker(workerIdentity())
                    .setCapabilities(
                        WorkerCapabilities.newBuilder()
                            .addSourceNamespaceIds(uuid(sourceNamespaceId)))
                    .setAvailableSlots(1))
            .build();

    var parsedRegistration = EstablishWorkerSessionRequest.parseFrom(registration.toByteArray());

    assertThat(parsedRegistration.getRegistration().getWorker()).isEqualTo(workerIdentity());
    assertThat(parsedRegistration.getRegistration().getCapabilities().getSourceNamespaceIdsList())
        .containsExactly(uuid(sourceNamespaceId));

    var streamSessionId = UUID.randomUUID();
    var jobId = UUID.randomUUID();
    var jobAttemptId = UUID.randomUUID();
    var start =
        StartVariantCommand.newBuilder()
            .setTarget(workerIdentity())
            .setJob(
                VariantJob.newBuilder()
                    .setStreamSessionId(uuid(streamSessionId))
                    .setJobId(uuid(jobId))
                    .setJobAttemptId(uuid(jobAttemptId))
                    .setSource(
                        MediaSourceRef.newBuilder()
                            .setSourceNamespaceId(uuid(sourceNamespaceId))
                            .setRelativeKey("movies/example.mkv")))
            .build();

    var parsedStart = StartVariantCommand.parseFrom(start.toByteArray());

    assertThat(parsedStart.getJob().getStreamSessionId()).isEqualTo(uuid(streamSessionId));
    assertThat(parsedStart.getJob().getJobId()).isEqualTo(uuid(jobId));
    assertThat(parsedStart.getJob().getJobAttemptId()).isEqualTo(uuid(jobAttemptId));
  }

  @Test
  @DisplayName(
      "Should advertise each worker-verified video encoder with its encoder backend when a worker registers")
  void shouldAdvertiseEachWorkerVerifiedVideoEncoderWithItsEncoderBackendWhenWorkerRegisters()
      throws Exception {
    var software =
        WorkerVerifiedVideoEncoder.newBuilder()
            .setBackend(
                encoderBackend(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")
                    .setKind(EncoderKind.ENCODER_KIND_SOFTWARE))
            .setKeyframeVerified(true)
            .build();
    var hardware =
        WorkerVerifiedVideoEncoder.newBuilder()
            .setBackend(
                encoderBackend(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC, "hevc_nvenc")
                    .setKind(EncoderKind.ENCODER_KIND_HARDWARE))
            .build();
    var capabilities =
        WorkerCapabilities.newBuilder()
            .addVideoEncoders(software)
            .addVideoEncoders(hardware)
            .build();

    var parsed = WorkerCapabilities.parseFrom(capabilities.toByteArray());

    assertThat(parsed.getVideoEncodersList()).containsExactly(software, hardware);
    assertThat(parsed.getVideoEncoders(1).getKeyframeVerified()).isFalse();
  }

  @Test
  @DisplayName("Should leave the encoder backend absent when a job attempt omits it")
  void shouldLeaveTheEncoderBackendAbsentWhenJobAttemptOmitsIt() throws Exception {
    var attemptId = uuid(UUID.randomUUID());
    var backend =
        encoderBackend(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "libsvtav1")
            .setKind(EncoderKind.ENCODER_KIND_SOFTWARE)
            .build();
    var encoding =
        JobAttemptStarted.newBuilder()
            .setJobAttemptId(attemptId)
            .setEncoderBackend(backend)
            .build();
    var copying = JobAttemptStarted.newBuilder().setJobAttemptId(attemptId).build();

    var parsedEncoding = JobAttemptStarted.parseFrom(encoding.toByteArray());
    var parsedCopying = JobAttemptStarted.parseFrom(copying.toByteArray());

    assertThat(parsedEncoding.getEncoderBackend()).isEqualTo(backend);
    assertThat(parsedCopying.hasEncoderBackend()).isFalse();
  }

  @Test
  @DisplayName(
      "Should preserve opaque media source keys byte for byte when validating the protocol")
  void shouldPreserveOpaqueMediaSourceKeysByteForByteWhenValidatingProtocol() throws Exception {
    var source =
        MediaSourceRef.newBuilder()
            .setSourceNamespaceId(uuid(UUID.randomUUID()))
            .setRelativeKey("../feature%2Ffilm.mkv")
            .build();

    assertThat(MediaSourceRef.parseFrom(source.toByteArray()).getRelativeKey())
        .isEqualTo("../feature%2Ffilm.mkv");
  }

  @Test
  @DisplayName(
      "Should upload segment bytes outside the worker control stream when validating the protocol")
  void shouldUploadSegmentBytesOutsideTheWorkerControlStreamWhenValidatingProtocol()
      throws Exception {
    var attemptId = uuid(UUID.randomUUID());
    var metadata =
        SegmentUploadMetadata.newBuilder()
            .setWorkerSessionId(uuid(UUID.randomUUID()))
            .setWorker(workerIdentity())
            .setStreamSessionId(uuid(UUID.randomUUID()))
            .setJobId(uuid(UUID.randomUUID()))
            .setJobAttemptId(attemptId)
            .setVariantLabel("720p")
            .setSegmentName("segment0.m4s")
            .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP4)
            .setContentLengthBytes(4)
            .build();
    var request = UploadSegmentRequest.newBuilder().setMetadata(metadata).build();

    var parsed = UploadSegmentRequest.parseFrom(request.toByteArray());

    assertThat(parsed.getMetadata().getJobAttemptId()).isEqualTo(attemptId);
    assertThat(parsed.getMetadata().getSegmentName()).isEqualTo("segment0.m4s");
  }

  @Test
  @DisplayName(
      "Should tell an encode without a codec profile apart from a copy when validating the protocol")
  void shouldTellAnEncodeWithoutACodecProfileApartFromACopyWhenValidatingTheProtocol()
      throws Exception {
    var encodeWithoutCodecProfile =
        VideoDecision.newBuilder()
            .setMode(VideoMode.VIDEO_MODE_ENCODE)
            .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264)
            .setCodecString("avc1.64002A")
            .build();
    var copy =
        VideoDecision.newBuilder()
            .setMode(VideoMode.VIDEO_MODE_COPY)
            .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264)
            .setCodecString("avc1.64002A")
            .build();

    var parsedEncode = VideoDecision.parseFrom(encodeWithoutCodecProfile.toByteArray());
    var parsedCopy = VideoDecision.parseFrom(copy.toByteArray());

    assertThat(parsedEncode.getMode()).isEqualTo(VideoMode.VIDEO_MODE_ENCODE);
    assertThat(parsedCopy.getMode()).isEqualTo(VideoMode.VIDEO_MODE_COPY);
    assertThat(parsedEncode).isNotEqualTo(parsedCopy);
    assertThat(parsedCopy.hasLevel()).isFalse();
  }

  @Test
  @DisplayName(
      "Should carry the source and planned output dynamic range when an encode converts an HDR source to SDR")
  void shouldCarrySourceAndPlannedOutputDynamicRangeWhenEncodeConvertsHdrSourceToSdr()
      throws Exception {
    var hdr10 =
        VideoDynamicRange.newBuilder()
            .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
            .setHdr10Plus(Presence.PRESENCE_UNSPECIFIED)
            .build();
    var sdr =
        VideoDynamicRange.newBuilder()
            .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
            .setHdr10Plus(Presence.PRESENCE_ABSENT)
            .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance())
            .build();
    var encode =
        VideoDecision.newBuilder()
            .setMode(VideoMode.VIDEO_MODE_ENCODE)
            .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264)
            .setCodecString("avc1.64002A")
            .setSourceDynamicRange(hdr10)
            .setOutputDynamicRange(sdr)
            .build();

    var decoded = VideoDecision.parseFrom(encode.toByteArray());

    assertThat(decoded.getSourceDynamicRange().getCompatibleBase())
        .isEqualTo(CompatibleBase.COMPATIBLE_BASE_PQ);
    assertThat(decoded.getSourceDynamicRange().getDolbyVisionCase())
        .isEqualTo(VideoDynamicRange.DolbyVisionCase.DOLBYVISION_NOT_SET);
    assertThat(decoded.getOutputDynamicRange()).isEqualTo(sdr);
  }

  @Test
  @DisplayName("Should leave the planned output dynamic range absent when the job copies the video")
  void shouldLeavePlannedOutputDynamicRangeAbsentWhenJobCopiesVideo() throws Exception {
    var copy =
        VideoDecision.newBuilder()
            .setMode(VideoMode.VIDEO_MODE_COPY)
            .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC)
            .setCodecString("hvc1.2.4.L153.B0")
            .setSourceDynamicRange(VideoDynamicRange.getDefaultInstance())
            .build();

    var decoded = VideoDecision.parseFrom(copy.toByteArray());

    assertThat(decoded.hasSourceDynamicRange()).isTrue();
    assertThat(decoded.getSourceDynamicRange().getCompatibleBase())
        .isEqualTo(CompatibleBase.COMPATIBLE_BASE_UNSPECIFIED);
    assertThat(decoded.hasOutputDynamicRange()).isFalse();
  }

  @Test
  @DisplayName(
      "Should name the chosen video stream and the exact output frame rate when a job encodes the video")
  void shouldNameChosenVideoStreamAndExactOutputFrameRateWhenJobEncodesVideo() throws Exception {
    var job =
        VariantJob.newBuilder()
            .setDecision(
                TranscodeDecision.newBuilder()
                    .setVideo(
                        VideoDecision.newBuilder()
                            .setMode(VideoMode.VIDEO_MODE_ENCODE)
                            .setStreamIndex(1)))
            .setExecution(
                TranscodeExecution.newBuilder()
                    .setOutputFrameRate(
                        Fraction.newBuilder().setNumerator(24_000).setDenominator(1_001)))
            .build();

    var decoded = VariantJob.parseFrom(job.toByteArray());

    assertThat(decoded.getDecision().getVideo().hasStreamIndex()).isTrue();
    assertThat(decoded.getDecision().getVideo().getStreamIndex()).isEqualTo(1);
    assertThat(decoded.getExecution().getOutputFrameRate().getNumerator()).isEqualTo(24_000);
    assertThat(decoded.getExecution().getOutputFrameRate().getDenominator()).isEqualTo(1_001);
    assertThat(VideoDecision.getDefaultInstance().hasStreamIndex()).isFalse();
    assertThat(TranscodeExecution.getDefaultInstance().hasOutputFrameRate()).isFalse();
  }

  @Test
  @DisplayName(
      "Should carry the field order and one frame per field when a job encodes an interlaced source")
  void shouldCarryTheFieldOrderAndOneFramePerFieldWhenJobEncodesAnInterlacedSource()
      throws Exception {
    var job =
        VariantJob.newBuilder()
            .setDecision(
                TranscodeDecision.newBuilder()
                    .setVideo(
                        VideoDecision.newBuilder()
                            .setMode(VideoMode.VIDEO_MODE_ENCODE)
                            .setDeinterlace(
                                Deinterlace.newBuilder()
                                    .setFieldOrder(FieldOrder.FIELD_ORDER_BB)
                                    .setOutput(
                                        DeinterlaceOutput.DEINTERLACE_OUTPUT_ONE_FRAME_PER_FIELD))))
            .setExecution(
                TranscodeExecution.newBuilder()
                    .setOutputFrameRate(
                        Fraction.newBuilder().setNumerator(60_000).setDenominator(1_001)))
            .build();

    var decoded = VariantJob.parseFrom(job.toByteArray());

    var deinterlace = decoded.getDecision().getVideo().getDeinterlace();
    assertThat(deinterlace.getFieldOrder()).isEqualTo(FieldOrder.FIELD_ORDER_BB);
    assertThat(deinterlace.getOutput())
        .isEqualTo(DeinterlaceOutput.DEINTERLACE_OUTPUT_ONE_FRAME_PER_FIELD);
    assertThat(decoded.getExecution().getOutputFrameRate().getNumerator()).isEqualTo(60_000);
  }

  @Test
  @DisplayName("Should leave the deinterlace instruction absent when a job omits it")
  void shouldLeaveTheDeinterlaceInstructionAbsentWhenJobOmitsIt() throws Exception {
    var progressiveEncode =
        VideoDecision.newBuilder()
            .setMode(VideoMode.VIDEO_MODE_ENCODE)
            .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264)
            .build();

    var decoded = VideoDecision.parseFrom(progressiveEncode.toByteArray());

    assertThat(decoded.hasDeinterlace()).isFalse();
    assertThat(decoded.getDeinterlace().getOutput())
        .isEqualTo(DeinterlaceOutput.DEINTERLACE_OUTPUT_UNSPECIFIED);
  }

  @Test
  @DisplayName(
      "Should advertise the stream measurement versions whose bitrate scan chunks a worker runs when a worker registers")
  void
      shouldAdvertiseTheStreamMeasurementVersionsWhoseBitrateScanChunksAWorkerRunsWhenWorkerRegisters()
          throws Exception {
    var measuring = WorkerCapabilities.newBuilder().addMeasurementVersions(1).build();
    var withoutBitrateScans = WorkerCapabilities.getDefaultInstance();

    var parsedMeasuring = WorkerCapabilities.parseFrom(measuring.toByteArray());
    var parsedWithoutBitrateScans = WorkerCapabilities.parseFrom(withoutBitrateScans.toByteArray());

    assertThat(parsedMeasuring.getMeasurementVersionsList()).containsExactly(1);
    assertThat(parsedWithoutBitrateScans.getMeasurementVersionsList()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should carry the chunk's keyframes and where its last interval ends when the server asks for a bitrate scan chunk")
  void shouldCarryTheChunkKeyframesAndWhereItsLastIntervalEndsWhenServerAsksForBitrateScanChunk()
      throws Exception {
    var chunk =
        BitrateScanChunk.newBuilder()
            .setMeasurementVersion(1)
            .setChosenVideoStreamIndex(0)
            .addMeasuredStreamIndexes(0)
            .addMeasuredStreamIndexes(1)
            .setTimeBaseNumerator(1)
            .setTimeBaseDenominator(90_000)
            .addPresentationTimeDeltas(-3_003)
            .addPresentationTimeDeltas(180_180)
            .addDecodeTimeDeltas(-6_006)
            .addDecodeTimeDeltas(180_180)
            .setEndPresentationTime(357_357)
            .setEndDecodeTime(354_354)
            .setFirstChunk(true)
            .build();
    var request =
        ProbeRequest.newBuilder()
            .setProbeAttemptId(uuid(UUID.randomUUID()))
            .setProbeVersion(7)
            .setPriority(ProbePriority.PROBE_PRIORITY_BACKGROUND)
            .setBitrateScanChunk(chunk)
            .build();

    var parsed = ProbeRequest.parseFrom(request.toByteArray());

    assertThat(parsed.getScanKeyframePackets()).isFalse();
    assertThat(parsed.getBitrateScanChunk()).isEqualTo(chunk);
    assertThat(parsed.getBitrateScanChunk().getPresentationTimeDeltasList())
        .containsExactly(-3_003L, 180_180L);
    assertThat(parsed.getBitrateScanChunk().hasEndPresentationTime()).isTrue();
    assertThat(parsed.getBitrateScanChunk().hasEndDecodeTime()).isTrue();
  }

  @Test
  @DisplayName(
      "Should leave the end times absent when the server asks for the last bitrate scan chunk")
  void shouldLeaveTheEndTimesAbsentWhenServerAsksForTheLastBitrateScanChunk() throws Exception {
    var lastChunk =
        BitrateScanChunk.newBuilder()
            .setMeasurementVersion(1)
            .addMeasuredStreamIndexes(0)
            .setTimeBaseNumerator(1)
            .setTimeBaseDenominator(1_000)
            .addPresentationTimeDeltas(0)
            .addDecodeTimeDeltas(0)
            .build();

    var parsed = BitrateScanChunk.parseFrom(lastChunk.toByteArray());

    assertThat(parsed.hasEndPresentationTime()).isFalse();
    assertThat(parsed.hasEndDecodeTime()).isFalse();
    assertThat(parsed.getFirstChunk()).isFalse();
    assertThat(ProbeRequest.getDefaultInstance().hasBitrateScanChunk()).isFalse();
  }

  @Test
  @DisplayName(
      "Should report each measured stream in place of media when a worker answers a bitrate scan chunk")
  void shouldReportEachMeasuredStreamInPlaceOfMediaWhenWorkerAnswersBitrateScanChunk()
      throws Exception {
    var video =
        BitrateScanChunkStream.newBuilder()
            .setStreamIndex(0)
            .setMeasuredBytes(4_194_304)
            .setPacketCount(240)
            .setTimeBaseNumerator(1)
            .setTimeBaseDenominator(90_000)
            .setFirstPresentationTime(-3_003)
            .setEndTime(357_357)
            .build();
    var silentAudio =
        BitrateScanChunkStream.newBuilder()
            .setStreamIndex(1)
            .setTimeBaseNumerator(1)
            .setTimeBaseDenominator(48_000)
            .build();
    var result =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(uuid(UUID.randomUUID()))
            .setProbeVersion(7)
            .setBitrateScanChunk(
                BitrateScanChunkResult.newBuilder()
                    .setMeasured(
                        BitrateScanChunkStreams.newBuilder()
                            .addStreams(video)
                            .addStreams(silentAudio)))
            .build();

    var parsed = ProbeAttemptResult.parseFrom(result.toByteArray());

    assertThat(parsed.getOutcomeCase())
        .isEqualTo(ProbeAttemptResult.OutcomeCase.BITRATE_SCAN_CHUNK);
    assertThat(parsed.hasMedia()).isFalse();
    assertThat(parsed.getBitrateScanChunk().getMeasured().getStreamsList())
        .containsExactly(video, silentAudio);
    assertThat(parsed.getBitrateScanChunk().getMeasured().getStreams(1).hasFirstPresentationTime())
        .isFalse();
    assertThat(parsed.getBitrateScanChunk().getMeasured().getStreams(1).hasEndTime()).isFalse();
  }

  @Test
  @DisplayName(
      "Should name the stream whose packets a copy would change when a worker cannot measure a bitrate scan chunk")
  void shouldNameTheStreamWhosePacketsACopyWouldChangeWhenWorkerCannotMeasureBitrateScanChunk()
      throws Exception {
    var result =
        BitrateScanChunkResult.newBuilder()
            .setCopyChangesPackets(BitrateScanCopyChangesPackets.newBuilder().setStreamIndex(2))
            .build();

    var parsed = BitrateScanChunkResult.parseFrom(result.toByteArray());

    assertThat(parsed.getResultCase())
        .isEqualTo(BitrateScanChunkResult.ResultCase.COPY_CHANGES_PACKETS);
    assertThat(parsed.getCopyChangesPackets().getStreamIndex()).isEqualTo(2);
    assertThat(parsed.hasMeasured()).isFalse();
  }

  @Test
  @DisplayName(
      "Should keep the field numbers, types and oneofs that workers build against when publishing the bitrate scan chunk contract")
  void
      shouldKeepTheFieldNumbersTypesAndOneofsThatWorkersBuildAgainstWhenPublishingBitrateScanChunkContract() {
    var request = ProbeRequest.getDescriptor().findFieldByName("bitrate_scan_chunk");
    var reply = ProbeAttemptResult.getDescriptor().findFieldByName("bitrate_scan_chunk");
    var versions = WorkerCapabilities.getDescriptor().findFieldByName("measurement_versions");
    var chunk = BitrateScanChunk.getDescriptor();
    var measured = BitrateScanChunkResult.getDescriptor().findFieldByName("measured");
    var copyChangesPackets =
        BitrateScanChunkResult.getDescriptor().findFieldByName("copy_changes_packets");
    var stream = BitrateScanChunkStream.getDescriptor();

    assertThat(request.getNumber()).isEqualTo(6);
    assertThat(reply.getNumber()).isEqualTo(5);
    assertThat(reply.getRealContainingOneof().getName()).isEqualTo("outcome");
    assertThat(versions.getNumber()).isEqualTo(4);
    assertThat(versions.getType()).isEqualTo(Type.UINT32);
    assertThat(versions.isRepeated()).isTrue();
    assertThat(chunk.findFieldByName("presentation_time_deltas").getNumber()).isEqualTo(6);
    assertThat(chunk.findFieldByName("decode_time_deltas").getType()).isEqualTo(Type.SINT64);
    assertThat(chunk.findFieldByName("end_presentation_time").getNumber()).isEqualTo(8);
    assertThat(chunk.findFieldByName("end_decode_time").getType()).isEqualTo(Type.SINT64);
    assertThat(chunk.findFieldByName("first_chunk").getNumber()).isEqualTo(10);
    assertThat(measured.getRealContainingOneof().getName()).isEqualTo("result");
    assertThat(measured.getNumber()).isEqualTo(1);
    assertThat(copyChangesPackets.getRealContainingOneof().getName()).isEqualTo("result");
    assertThat(copyChangesPackets.getNumber()).isEqualTo(2);
    assertThat(stream.findFieldByName("measured_bytes").getType()).isEqualTo(Type.UINT64);
    assertThat(stream.findFieldByName("first_presentation_time").getType()).isEqualTo(Type.SINT64);
    assertThat(stream.findFieldByName("end_time").getNumber()).isEqualTo(7);
  }

  @Test
  @DisplayName(
      "Should carry the advertised end with a held last frame and silence padding when a job encodes both chosen streams")
  void
      shouldCarryTheAdvertisedEndWithAHeldLastFrameAndSilencePaddingWhenJobEncodesBothChosenStreams()
          throws Exception {
    var job =
        VariantJob.newBuilder()
            .setDecision(
                TranscodeDecision.newBuilder()
                    .setVideo(
                        VideoDecision.newBuilder()
                            .setMode(VideoMode.VIDEO_MODE_ENCODE)
                            .setHoldLastFrameToAdvertisedEnd(true))
                    .setAudio(
                        AudioDecision.newBuilder()
                            .setMode(AudioMode.AUDIO_MODE_TRANSCODE)
                            .setPadWithSilenceToAdvertisedEnd(true)))
            .setExecution(
                TranscodeExecution.newBuilder()
                    .setMediaSegmentCount(14)
                    .setAdvertisedEnd(Duration.newBuilder().setSeconds(84)))
            .build();

    var decoded = VariantJob.parseFrom(job.toByteArray());

    assertThat(decoded.getDecision().getVideo().getHoldLastFrameToAdvertisedEnd()).isTrue();
    assertThat(decoded.getDecision().getAudio().getPadWithSilenceToAdvertisedEnd()).isTrue();
    assertThat(decoded.getExecution().getAdvertisedEnd().getSeconds()).isEqualTo(84);
  }

  @Test
  @DisplayName(
      "Should carry the keyframe to read from apart from the first segment's start when an attempt that holds the last frame starts after the video's end")
  void
      shouldCarryTheKeyframeToReadFromApartFromTheFirstSegmentStartWhenHoldingAttemptStartsAfterVideoEnd()
          throws Exception {
    // The video's last keyframe is at 60.06 s, so the attempt reads from the keyframe before it.
    var keyframeBeforeLastKeyframe = Duration.newBuilder().setSeconds(54).setNanos(54_000_000);
    var job =
        VariantJob.newBuilder()
            .setDecision(
                TranscodeDecision.newBuilder()
                    .setVideo(
                        VideoDecision.newBuilder()
                            .setMode(VideoMode.VIDEO_MODE_ENCODE)
                            .setHoldLastFrameToAdvertisedEnd(true)))
            .setExecution(
                TranscodeExecution.newBuilder()
                    .setStartSequenceNumber(11)
                    .setSeekPositionSeconds(66)
                    .setSeekPosition(keyframeBeforeLastKeyframe)
                    .setMediaSegmentCount(14)
                    .setAdvertisedEnd(Duration.newBuilder().setSeconds(84)))
            .build();

    var decoded = VariantJob.parseFrom(job.toByteArray()).getExecution();

    assertThat(decoded.getSeekPosition()).isEqualTo(keyframeBeforeLastKeyframe.build());
    assertThat(decoded.getSeekPositionSeconds()).isEqualTo(66);
    assertThat(decoded.getStartSequenceNumber()).isEqualTo(11);
  }

  @Test
  @DisplayName(
      "Should carry the source snapshot that the stream session's probe read when a job is sent")
  void shouldCarryTheSourceSnapshotThatTheStreamSessionProbeReadWhenJobIsSent() throws Exception {
    var modifiedAt = Timestamp.newBuilder().setSeconds(1_793_000_000L).setNanos(123_456_789);
    var job =
        VariantJob.newBuilder()
            .setSourceSnapshot(
                SourceSnapshot.newBuilder().setSizeBytes(8_589_934_592L).setModifiedAt(modifiedAt))
            .build();

    var decoded = VariantJob.parseFrom(job.toByteArray());

    assertThat(decoded.hasSourceSnapshot()).isTrue();
    assertThat(decoded.getSourceSnapshot().getSizeBytes()).isEqualTo(8_589_934_592L);
    assertThat(decoded.getSourceSnapshot().getModifiedAt()).isEqualTo(modifiedAt.build());
    assertThat(VariantJob.getDefaultInstance().hasSourceSnapshot()).isFalse();
  }

  @Test
  @DisplayName(
      "Should name the chosen audio stream by its container index when a job copies or encodes the audio")
  void shouldNameTheChosenAudioStreamByItsContainerIndexWhenJobCopiesOrEncodesTheAudio()
      throws Exception {
    var copy = AudioDecision.newBuilder().setMode(AudioMode.AUDIO_MODE_COPY).setStreamIndex(2);
    var encode =
        AudioDecision.newBuilder().setMode(AudioMode.AUDIO_MODE_TRANSCODE).setStreamIndex(0);

    var decodedCopy = AudioDecision.parseFrom(copy.build().toByteArray());
    var decodedEncode = AudioDecision.parseFrom(encode.build().toByteArray());

    assertThat(decodedCopy.getStreamIndex()).isEqualTo(2);
    assertThat(decodedEncode.hasStreamIndex()).isTrue();
    assertThat(decodedEncode.getStreamIndex()).isZero();
    assertThat(AudioDecision.newBuilder().setMode(AudioMode.AUDIO_MODE_NONE).hasStreamIndex())
        .isFalse();
  }

  @Test
  @DisplayName(
      "Should report each chosen stream's end when an attempt completes through the last advertised segment")
  void shouldReportEachChosenStreamEndWhenAttemptCompletesThroughTheLastAdvertisedSegment()
      throws Exception {
    var video =
        SourceStreamEnd.newBuilder()
            .setStreamIndex(0)
            .setEndTime(Duration.newBuilder().setSeconds(84))
            .build();
    var audioEndingEarly =
        SourceStreamEnd.newBuilder()
            .setStreamIndex(1)
            .setEndTime(Duration.newBuilder().setSeconds(20))
            .build();
    var completed =
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptCompleted(
                JobAttemptCompleted.newBuilder()
                    .setJobAttemptId(uuid(UUID.randomUUID()))
                    .addStreamEnds(video)
                    .addStreamEnds(audioEndingEarly))
            .build();

    var parsed = EstablishWorkerSessionRequest.parseFrom(completed.toByteArray());

    assertThat(parsed.getJobAttemptCompleted().getStreamEndsList())
        .containsExactly(video, audioEndingEarly);
    assertThat(parsed.getJobAttemptCompleted().hasSourceEndedEarly()).isFalse();
  }

  @Test
  @DisplayName(
      "Should report the last segment with source media when an encode's source ends before its last advertised segment")
  void shouldReportTheLastSegmentWithSourceMediaWhenEncodeSourceEndsBeforeItsLastAdvertisedSegment()
      throws Exception {
    var completed =
        JobAttemptCompleted.newBuilder()
            .setJobAttemptId(uuid(UUID.randomUUID()))
            .setSourceEndedEarly(SourceEndedEarly.newBuilder().setLastSegmentWithSourceMedia(10))
            .addStreamEnds(
                SourceStreamEnd.newBuilder()
                    .setStreamIndex(0)
                    .setEndTime(Duration.newBuilder().setSeconds(64)))
            .build();

    var parsed = JobAttemptCompleted.parseFrom(completed.toByteArray());

    assertThat(parsed.getSourceEndedEarly().getLastSegmentWithSourceMedia()).isEqualTo(10);
    assertThat(parsed.getSourceEndedEarly().getStreamIndexesWithMediaAfterLastSegmentList())
        .isEmpty();
  }

  @Test
  @DisplayName(
      "Should name the stream that still had media when a copy's source ends before its last advertised segment")
  void shouldNameTheStreamThatStillHadMediaWhenCopySourceEndsBeforeItsLastAdvertisedSegment()
      throws Exception {
    var report =
        SourceEndedEarly.newBuilder()
            .setLastSegmentWithSourceMedia(10)
            .addStreamIndexesWithMediaAfterLastSegment(1)
            .build();

    var parsed = SourceEndedEarly.parseFrom(report.toByteArray());

    assertThat(parsed.getStreamIndexesWithMediaAfterLastSegmentList()).containsExactly(1);
  }

  @Test
  @DisplayName(
      "Should tell a report that names no last segment apart from one that names segment 0 when a worker reports that the source ended early")
  void shouldTellAReportThatNamesNoLastSegmentApartFromOneThatNamesSegmentZeroWhenSourceEndedEarly()
      throws Exception {
    var withoutLastSegment = SourceEndedEarly.newBuilder().build();
    var namingSegmentZero = SourceEndedEarly.newBuilder().setLastSegmentWithSourceMedia(0).build();

    var parsedWithout = SourceEndedEarly.parseFrom(withoutLastSegment.toByteArray());
    var parsedZero = SourceEndedEarly.parseFrom(namingSegmentZero.toByteArray());

    assertThat(parsedWithout.hasLastSegmentWithSourceMedia()).isFalse();
    assertThat(parsedZero.hasLastSegmentWithSourceMedia()).isTrue();
    assertThat(parsedZero.getLastSegmentWithSourceMedia()).isZero();
  }

  @Test
  @DisplayName(
      "Should report the audio stream and the read position beside the video's end when a padded encode reads no audio sample")
  void
      shouldReportTheAudioStreamAndTheReadPositionBesideTheVideoEndWhenPaddedEncodeReadsNoAudioSample()
          throws Exception {
    var readFrom = Duration.newBuilder().setSeconds(54).setNanos(54_000_000).build();
    var audioWithoutSamples =
        SourceStreamWithoutSamples.newBuilder().setStreamIndex(1).setReadFrom(readFrom).build();
    var video =
        SourceStreamEnd.newBuilder()
            .setStreamIndex(0)
            .setEndTime(Duration.newBuilder().setSeconds(84))
            .build();
    var completed =
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptCompleted(
                JobAttemptCompleted.newBuilder()
                    .setJobAttemptId(uuid(UUID.randomUUID()))
                    .addStreamEnds(video)
                    .addStreamsWithoutSamples(audioWithoutSamples))
            .build();

    var parsed =
        EstablishWorkerSessionRequest.parseFrom(completed.toByteArray()).getJobAttemptCompleted();

    assertThat(parsed.getStreamsWithoutSamplesList()).containsExactly(audioWithoutSamples);
    assertThat(parsed.getStreamEndsList()).containsExactly(video);
  }

  @Test
  @DisplayName(
      "Should carry the stream and the read position with the failure when an attempt can't stand in for a stream that delivered no sample")
  void shouldCarryTheStreamAndTheReadPositionWithTheFailureWhenAttemptCannotStandInForStream()
      throws Exception {
    var copiedAudio =
        SourceStreamWithoutSamples.newBuilder()
            .setStreamIndex(0)
            .setReadFrom(Duration.newBuilder().setSeconds(30).setNanos(30_000_000))
            .build();
    var failed =
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptFailed(
                JobAttemptFailed.newBuilder()
                    .setJobAttemptId(uuid(UUID.randomUUID()))
                    .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_STREAM_WITHOUT_SAMPLES)
                    .setStreamWithoutSamples(copiedAudio))
            .build();

    var parsed =
        EstablishWorkerSessionRequest.parseFrom(failed.toByteArray()).getJobAttemptFailed();

    assertThat(parsed.getFailure())
        .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_STREAM_WITHOUT_SAMPLES);
    assertThat(parsed.getStreamWithoutSamples()).isEqualTo(copiedAudio);
    assertThat(parsed.getStreamWithoutSamples().hasStreamIndex()).isTrue();
    assertThat(JobAttemptFailed.getDefaultInstance().hasStreamWithoutSamples()).isFalse();
  }

  @Test
  @DisplayName(
      "Should keep the field numbers and types that workers build against when publishing the early end contract")
  void shouldKeepTheFieldNumbersAndTypesThatWorkersBuildAgainstWhenPublishingTheEarlyEndContract() {
    var hold = VideoDecision.getDescriptor().findFieldByName("hold_last_frame_to_advertised_end");
    var pad = AudioDecision.getDescriptor().findFieldByName("pad_with_silence_to_advertised_end");
    var audioStreamIndex = AudioDecision.getDescriptor().findFieldByName("stream_index");
    var sourceSnapshot = VariantJob.getDescriptor().findFieldByName("source_snapshot");
    var snapshot = SourceSnapshot.getDescriptor();
    var advertisedEnd = TranscodeExecution.getDescriptor().findFieldByName("advertised_end");
    var completed = JobAttemptCompleted.getDescriptor();
    var report = SourceEndedEarly.getDescriptor();
    var lastSegment = report.findFieldByName("last_segment_with_source_media");
    var streamEnd = SourceStreamEnd.getDescriptor();

    assertThat(hold.getNumber()).isEqualTo(14);
    assertThat(hold.getType()).isEqualTo(Type.BOOL);
    assertThat(pad.getNumber()).isEqualTo(6);
    assertThat(pad.getType()).isEqualTo(Type.BOOL);
    assertThat(audioStreamIndex.getNumber()).isEqualTo(7);
    assertThat(audioStreamIndex.getType()).isEqualTo(Type.UINT32);
    assertThat(audioStreamIndex.hasPresence()).isTrue();
    assertThat(sourceSnapshot.getNumber()).isEqualTo(8);
    assertThat(snapshot.findFieldByName("size_bytes").getNumber()).isEqualTo(1);
    assertThat(snapshot.findFieldByName("size_bytes").getType()).isEqualTo(Type.UINT64);
    assertThat(snapshot.findFieldByName("modified_at").getNumber()).isEqualTo(2);
    assertThat(snapshot.findFieldByName("modified_at").getMessageType())
        .isEqualTo(Timestamp.getDescriptor());
    assertThat(advertisedEnd.getNumber()).isEqualTo(8);
    assertThat(advertisedEnd.getMessageType()).isEqualTo(Duration.getDescriptor());
    assertThat(completed.findFieldByName("source_ended_early").getNumber()).isEqualTo(2);
    assertThat(completed.findFieldByName("stream_ends").getNumber()).isEqualTo(3);
    assertThat(lastSegment.getNumber()).isEqualTo(1);
    assertThat(lastSegment.getType()).isEqualTo(Type.UINT32);
    assertThat(lastSegment.hasPresence()).isTrue();
    assertThat(report.findFieldByName("stream_indexes_with_media_after_last_segment").getNumber())
        .isEqualTo(2);
    assertThat(streamEnd.findFieldByName("stream_index").getNumber()).isEqualTo(1);
    assertThat(streamEnd.findFieldByName("end_time").getNumber()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should keep the field numbers and types that workers build against when publishing the report of a stream that delivered no sample")
  void
      shouldKeepTheFieldNumbersAndTypesThatWorkersBuildAgainstWhenPublishingStreamWithoutSamples() {
    var withoutSamples = SourceStreamWithoutSamples.getDescriptor();
    var streamIndex = withoutSamples.findFieldByName("stream_index");
    var readFrom = withoutSamples.findFieldByName("read_from");
    var completedReport =
        JobAttemptCompleted.getDescriptor().findFieldByName("streams_without_samples");
    var failedReport = JobAttemptFailed.getDescriptor().findFieldByName("stream_without_samples");

    assertThat(streamIndex.getNumber()).isEqualTo(1);
    assertThat(streamIndex.getType()).isEqualTo(Type.UINT32);
    assertThat(streamIndex.hasPresence()).isTrue();
    assertThat(readFrom.getNumber()).isEqualTo(2);
    assertThat(readFrom.getMessageType()).isEqualTo(Duration.getDescriptor());
    assertThat(completedReport.getNumber()).isEqualTo(4);
    assertThat(completedReport.isRepeated()).isTrue();
    assertThat(completedReport.getMessageType()).isEqualTo(withoutSamples);
    assertThat(failedReport.getNumber()).isEqualTo(3);
    assertThat(failedReport.isRepeated()).isFalse();
    assertThat(failedReport.getMessageType()).isEqualTo(withoutSamples);
    assertThat(JobAttemptFailure.JOB_ATTEMPT_FAILURE_STREAM_WITHOUT_SAMPLES.getNumber())
        .isEqualTo(7);
  }

  @Test
  @DisplayName("Should leave the segment list empty when a job uses the plain grid")
  void shouldLeaveTheSegmentListEmptyWhenJobUsesThePlainGrid() throws Exception {
    var plainGrid =
        TranscodeExecution.newBuilder()
            .setTargetSegmentDurationSeconds(3)
            .setMediaSegmentCount(28)
            .build();

    var decoded = TranscodeExecution.parseFrom(plainGrid.toByteArray());

    assertThat(decoded.getSegmentStartTicksList()).isEmpty();
    assertThat(decoded.hasSegmentStartTimeBase()).isFalse();
  }

  @Test
  @DisplayName(
      "Should carry each segment's exact start in ticks of the source video stream's time base when a stream session copies the video")
  void
      shouldCarryEachSegmentsExactStartInTicksOfTheSourceVideoStreamsTimeBaseWhenSessionCopiesVideo()
          throws Exception {
    // Keyframes at 0, 3.5, 6.5 and 9.5 s on a 90 kHz time base, and one at about 14 h whose tick
    // count needs more than 32 bits.
    var execution =
        TranscodeExecution.newBuilder()
            .setTargetSegmentDurationSeconds(3)
            .setMediaSegmentCount(5)
            .addAllSegmentStartTicks(List.of(0L, 315_000L, 585_000L, 855_000L, 4_536_000_000L))
            .setSegmentStartTimeBase(Fraction.newBuilder().setNumerator(1).setDenominator(90_000))
            .build();

    var decoded = TranscodeExecution.parseFrom(execution.toByteArray());

    assertThat(decoded.getSegmentStartTicksList())
        .containsExactly(0L, 315_000L, 585_000L, 855_000L, 4_536_000_000L);
    assertThat(decoded.getSegmentStartTicksCount()).isEqualTo(decoded.getMediaSegmentCount());
    assertThat(decoded.getSegmentStartTimeBase().getNumerator()).isEqualTo(1);
    assertThat(decoded.getSegmentStartTimeBase().getDenominator()).isEqualTo(90_000);
  }

  @Test
  @DisplayName(
      "Should keep the field numbers and types that workers build against when publishing exact segment starts")
  void shouldKeepTheFieldNumbersAndTypesThatWorkersBuildAgainstWhenPublishingExactSegmentStarts() {
    var execution = TranscodeExecution.getDescriptor();
    var startTicks = execution.findFieldByName("segment_start_ticks");
    var timeBase = execution.findFieldByName("segment_start_time_base");

    assertThat(startTicks.getNumber()).isEqualTo(10);
    assertThat(startTicks.getType()).isEqualTo(Type.UINT64);
    assertThat(startTicks.isRepeated()).isTrue();
    assertThat(startTicks.isPacked()).isTrue();
    assertThat(timeBase.getNumber()).isEqualTo(11);
    assertThat(timeBase.getMessageType()).isEqualTo(Fraction.getDescriptor());
    assertThat(timeBase.hasPresence()).isTrue();
  }

  @Test
  @DisplayName(
      "Should reserve the number and name of the slot list when exact segment starts replace it")
  void shouldReserveTheNumberAndNameOfTheSlotListWhenExactSegmentStartsReplaceIt() {
    var execution = TranscodeExecution.getDescriptor();
    var declared = execution.toProto();

    assertThat(execution.findFieldByNumber(9)).isNull();
    assertThat(execution.findFieldByName("segment_start_slots")).isNull();
    assertThat(declared.getReservedRangeList())
        .anySatisfy(
            range ->
                assertThat(9).isGreaterThanOrEqualTo(range.getStart()).isLessThan(range.getEnd()));
    assertThat(declared.getReservedNameList()).contains("segment_start_slots");
  }

  @Test
  @DisplayName("Should distinguish short copied audio from failed video when an attempt fails")
  void shouldDistinguishShortCopiedAudioFromFailedVideoWhenAnAttemptFails() throws Exception {
    var failure =
        JobAttemptFailed.newBuilder()
            .setJobAttemptId(uuid(UUID.randomUUID()))
            .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_COPIED_AUDIO_RAN_SHORT)
            .build();

    var parsed = JobAttemptFailed.parseFrom(failure.toByteArray());

    assertThat(parsed.getFailure())
        .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_COPIED_AUDIO_RAN_SHORT)
        .isNotEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_OUTPUT_MISMATCH);
    assertThat(parsed.getFailureValue()).isEqualTo(11);
  }

  @Test
  @DisplayName(
      "Should tell a segment over the segment cap apart from a generic transcode failure when an attempt fails")
  void shouldTellASegmentOverTheSegmentCapApartFromAGenericTranscodeFailureWhenAttemptFails()
      throws Exception {
    var overCap =
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptFailed(
                JobAttemptFailed.newBuilder()
                    .setJobAttemptId(uuid(UUID.randomUUID()))
                    .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_SEGMENT_CAP_EXCEEDED))
            .build();

    var parsed = EstablishWorkerSessionRequest.parseFrom(overCap.toByteArray());

    assertThat(parsed.getJobAttemptFailed().getFailure())
        .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_SEGMENT_CAP_EXCEEDED)
        .isNotEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
    assertThat(JobAttemptFailure.JOB_ATTEMPT_FAILURE_SEGMENT_CAP_EXCEEDED.getNumber()).isEqualTo(6);
  }

  @Test
  @DisplayName(
      "Should tell a copy refused at the copy cap apart from a startup failure when a worker refuses a copy")
  void shouldTellACopyRefusedAtTheCopyCapApartFromAStartupFailureWhenWorkerRefusesACopy()
      throws Exception {
    var refusedCopy =
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptFailed(
                JobAttemptFailed.newBuilder()
                    .setJobAttemptId(uuid(UUID.randomUUID()))
                    .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_COPY_CAP_REACHED))
            .build();

    var parsed = EstablishWorkerSessionRequest.parseFrom(refusedCopy.toByteArray());

    assertThat(parsed.getJobAttemptFailed().getFailure())
        .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_COPY_CAP_REACHED)
        .isNotEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_STARTUP_FAILED);
    assertThat(JobAttemptFailure.JOB_ATTEMPT_FAILURE_COPY_CAP_REACHED.getNumber()).isEqualTo(8);
  }

  @Test
  @DisplayName(
      "Should carry the worker's segment upload limit when the server accepts a worker session")
  void shouldCarryTheWorkersSegmentUploadLimitWhenServerAcceptsAWorkerSession() throws Exception {
    var accepted =
        EstablishWorkerSessionResponse.newBuilder()
            .setSessionAccepted(
                WorkerSessionAccepted.newBuilder()
                    .setWorkerSessionId(uuid(UUID.randomUUID()))
                    .setSegmentUploadLimit(8))
            .build();

    var parsed =
        EstablishWorkerSessionResponse.parseFrom(accepted.toByteArray()).getSessionAccepted();

    assertThat(parsed.getSegmentUploadLimit()).isEqualTo(8);
  }

  @Test
  @DisplayName(
      "Should keep the field number and type that workers build against when publishing the segment upload limit")
  void shouldKeepTheFieldNumberAndTypeThatWorkersBuildAgainstWhenPublishingTheSegmentUploadLimit() {
    var uploadLimit = WorkerSessionAccepted.getDescriptor().findFieldByName("segment_upload_limit");

    assertThat(uploadLimit.getNumber()).isEqualTo(2);
    assertThat(uploadLimit.getType()).isEqualTo(Type.UINT32);
    assertThat(uploadLimit.isRepeated()).isFalse();
    assertThat(uploadLimit.hasPresence()).isFalse();
  }

  @Test
  @DisplayName(
      "Should let a worker find the segment upload limit marker under its published type when the server refuses an upload")
  void
      shouldLetAWorkerFindTheSegmentUploadLimitMarkerUnderItsPublishedTypeWhenServerRefusesAnUpload()
          throws Exception {
    var refusal =
        Status.newBuilder()
            .setCode(Code.RESOURCE_EXHAUSTED_VALUE)
            .addDetails(Any.pack(SegmentUploadLimitReached.getDefaultInstance()))
            .build();

    var parsed = Status.parseFrom(refusal.toByteArray());

    assertThat(parsed.getDetailsList())
        .singleElement()
        .matches(detail -> detail.is(SegmentUploadLimitReached.class));
    // A worker matches the detail by its full name, so the package and the name are the contract.
    assertThat(SegmentUploadLimitReached.getDescriptor().getFile().getPackage())
        .isEqualTo("streamarr.transcode.v1");
    assertThat(SegmentUploadLimitReached.getDescriptor().getName())
        .isEqualTo("SegmentUploadLimitReached");
  }

  @Test
  @DisplayName(
      "Should carry the media time that the output reached when a worker reports an attempt's progress inside an open segment")
  void
      shouldCarryTheMediaTimeThatTheOutputReachedWhenWorkerReportsAttemptProgressInsideOpenSegment()
          throws Exception {
    var attemptId = uuid(UUID.randomUUID());
    // A merged segment runs from 12 s to 72 s, and the output has reached 41.041 s inside it.
    var outputEnd = Duration.newBuilder().setSeconds(41).setNanos(41_000_000).build();
    var progress =
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptProgress(
                JobAttemptProgress.newBuilder().setJobAttemptId(attemptId).setOutputEnd(outputEnd))
            .build();

    var parsed = EstablishWorkerSessionRequest.parseFrom(progress.toByteArray());

    assertThat(parsed.getEventCase())
        .isEqualTo(EstablishWorkerSessionRequest.EventCase.JOB_ATTEMPT_PROGRESS);
    assertThat(parsed.getJobAttemptProgress().getJobAttemptId()).isEqualTo(attemptId);
    assertThat(parsed.getJobAttemptProgress().getOutputEnd()).isEqualTo(outputEnd);
  }

  @Test
  @DisplayName(
      "Should carry the source bytes read and no output time when a held encode reports its keyframe search as progress")
  void
      shouldCarryTheSourceBytesReadAndNoOutputTimeWhenHeldEncodeReportsItsKeyframeSearchAsProgress()
          throws Exception {
    var searching =
        JobAttemptProgress.newBuilder()
            .setJobAttemptId(uuid(UUID.randomUUID()))
            .setKeyframeSearchBytesRead(268_435_456L)
            .build();

    var parsed = JobAttemptProgress.parseFrom(searching.toByteArray());

    assertThat(parsed.getKeyframeSearchBytesRead()).isEqualTo(268_435_456L);
    assertThat(parsed.hasOutputEnd()).isFalse();
  }

  @Test
  @DisplayName(
      "Should carry the bytes that FFmpeg has read and no output time when an attempt reports its reading as progress")
  void shouldCarryTheBytesThatFfmpegHasReadAndNoOutputTimeWhenAnAttemptReportsItsReadingAsProgress()
      throws Exception {
    var reading =
        JobAttemptProgress.newBuilder()
            .setJobAttemptId(uuid(UUID.randomUUID()))
            .setFfmpegBytesRead(3_005_026_741L)
            .build();

    var parsed = JobAttemptProgress.parseFrom(reading.toByteArray());

    assertThat(parsed.getFfmpegBytesRead()).isEqualTo(3_005_026_741L);
    assertThat(parsed.hasOutputEnd()).isFalse();
    assertThat(parsed.getKeyframeSearchBytesRead()).isZero();
  }

  @Test
  @DisplayName(
      "Should keep the field numbers and types that workers build against when publishing the progress report")
  void shouldKeepTheFieldNumbersAndTypesThatWorkersBuildAgainstWhenPublishingTheProgressReport() {
    var event =
        EstablishWorkerSessionRequest.getDescriptor().findFieldByName("job_attempt_progress");
    var progress = JobAttemptProgress.getDescriptor();

    assertThat(event.getNumber()).isEqualTo(10);
    assertThat(event.getContainingOneof().getName()).isEqualTo("event");
    assertThat(event.getMessageType()).isEqualTo(progress);
    assertThat(progress.findFieldByName("job_attempt_id").getNumber()).isEqualTo(1);
    assertThat(progress.findFieldByName("job_attempt_id").getMessageType())
        .isEqualTo(Uuid.getDescriptor());
    assertThat(progress.findFieldByName("output_end").getNumber()).isEqualTo(2);
    assertThat(progress.findFieldByName("output_end").getMessageType())
        .isEqualTo(Duration.getDescriptor());
    assertThat(progress.findFieldByName("keyframe_search_bytes_read").getNumber()).isEqualTo(3);
    assertThat(progress.findFieldByName("keyframe_search_bytes_read").getType())
        .isEqualTo(Type.UINT64);
    assertThat(progress.findFieldByName("ffmpeg_bytes_read"))
        .as("the field that counts the bytes that an attempt's FFmpeg has read")
        .isNotNull();
    assertThat(progress.findFieldByName("ffmpeg_bytes_read").getNumber()).isEqualTo(4);
    assertThat(progress.findFieldByName("ffmpeg_bytes_read").getType()).isEqualTo(Type.UINT64);
  }

  @Test
  @DisplayName(
      "Should keep the wire vocabulary narrow and secret free when validating the protocol")
  void shouldKeepTheWireVocabularyNarrowAndSecretFreeWhenValidatingProtocol() {
    var fields = new HashSet<String>();
    EstablishWorkerSessionRequest.getDescriptor()
        .getFile()
        .getMessageTypes()
        .forEach(descriptor -> collectFields(descriptor, fields));

    assertThat(fields)
        .contains(
            "worker",
            "target",
            "stream_session_id",
            "job_id",
            "job_attempt_id",
            "probe_attempt_id",
            "probe_version",
            "probe_versions",
            "start_probe",
            "probe_result",
            "accepted_length_bytes",
            "encoder_preset")
        .doesNotContain(
            "session_id",
            "source_path",
            "host_path",
            "command",
            "command_line",
            "command_arguments",
            "arguments",
            "args",
            "json",
            "raw_json",
            "process_id",
            "token",
            "password",
            "secret");
  }

  private void collectFields(Descriptor descriptor, Set<String> fields) {
    descriptor.getFields().forEach(field -> fields.add(field.getName()));
    descriptor.getNestedTypes().forEach(nested -> collectFields(nested, fields));
  }

  private WorkerIdentity workerIdentity() {
    return WorkerIdentity.newBuilder()
        .setWorkerId(uuid(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")))
        .setBootId(uuid(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")))
        .build();
  }

  private VideoEncoderBackend.Builder encoderBackend(VideoCodecFamily codecFamily, String encoder) {
    return VideoEncoderBackend.newBuilder().setCodecFamily(codecFamily).setEncoder(encoder);
  }

  private Uuid uuid(UUID value) {
    return Uuid.newBuilder()
        .setMostSignificantBits(value.getMostSignificantBits())
        .setLeastSignificantBits(value.getLeastSignificantBits())
        .build();
  }

  private static final Path WORKER_SESSION_PROTO =
      Path.of("src/main/protobuf/streamarr/transcode/v1/worker_session.proto");
  private static final Pattern DOCUMENTED_LAYOUT =
      Pattern.compile("// (\\S+): ([A-Z+]+)\\.\\R\\s*(AUDIO_CHANNEL_LAYOUT_\\w+) = \\d+;");

  @Test
  @DisplayName(
      "Should name the output channel layout and its channel count when a job encodes audio")
  void shouldNameTheOutputChannelLayoutAndItsChannelCountWhenJobEncodesAudio() throws Exception {
    var job =
        VariantJob.newBuilder()
            .setDecision(
                TranscodeDecision.newBuilder()
                    .setAudio(
                        AudioDecision.newBuilder()
                            .setMode(AudioMode.AUDIO_MODE_TRANSCODE)
                            .setCodec("eac3")
                            .setChannels(4)
                            .setBitrateBitsPerSecond(256_000)
                            .setChannelLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_QUAD)))
            .build();

    var audio = VariantJob.parseFrom(job.toByteArray()).getDecision().getAudio();

    assertThat(audio.getChannelLayout()).isEqualTo(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_QUAD);
    assertThat(audio.getChannels()).isEqualTo(4);
  }

  @Test
  @DisplayName(
      "Should read an audio encode without a channel layout as unspecified when an older server sends it")
  void shouldReadAudioEncodeWithoutChannelLayoutAsUnspecifiedWhenOlderServerSendsIt()
      throws Exception {
    var olderServerEncode =
        AudioDecision.newBuilder()
            .setMode(AudioMode.AUDIO_MODE_TRANSCODE)
            .setCodec("aac")
            .setChannels(2)
            .build();

    var decoded = AudioDecision.parseFrom(olderServerEncode.toByteArray());

    assertThat(decoded.getChannelLayout())
        .isEqualTo(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_UNSPECIFIED);
    assertThat(decoded.getChannels()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should keep the field numbers, types and layout values that workers build against when publishing the channel layout contract")
  void
      shouldKeepTheFieldNumbersTypesAndLayoutValuesThatWorkersBuildAgainstWhenPublishingChannelLayoutContract() {
    var probedLayout = ProbeStreamInfo.getDescriptor().findFieldByName("channel_layout");
    var outputLayout = AudioDecision.getDescriptor().findFieldByName("channel_layout");

    assertThat(probedLayout.getNumber()).isEqualTo(38);
    assertThat(probedLayout.getType()).isEqualTo(Type.STRING);
    assertThat(probedLayout.hasPresence()).isTrue();
    assertThat(outputLayout.getNumber()).isEqualTo(5);
    assertThat(outputLayout.getEnumType()).isEqualTo(AudioChannelLayout.getDescriptor());
    assertThat(AudioChannelLayout.getDescriptor().getValues())
        .extracting(EnumValueDescriptor::getName, EnumValueDescriptor::getNumber)
        .containsExactly(
            tuple("AUDIO_CHANNEL_LAYOUT_UNSPECIFIED", 0),
            tuple("AUDIO_CHANNEL_LAYOUT_MONO", 1),
            tuple("AUDIO_CHANNEL_LAYOUT_STEREO", 2),
            tuple("AUDIO_CHANNEL_LAYOUT_TWO_POINT_ONE", 3),
            tuple("AUDIO_CHANNEL_LAYOUT_THREE_POINT_ZERO", 4),
            tuple("AUDIO_CHANNEL_LAYOUT_THREE_POINT_ZERO_BACK", 5),
            tuple("AUDIO_CHANNEL_LAYOUT_FOUR_POINT_ZERO", 6),
            tuple("AUDIO_CHANNEL_LAYOUT_QUAD", 7),
            tuple("AUDIO_CHANNEL_LAYOUT_QUAD_SIDE", 8),
            tuple("AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE", 9),
            tuple("AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE_SIDE", 10),
            tuple("AUDIO_CHANNEL_LAYOUT_SEVEN_POINT_ONE", 11),
            tuple("AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO", 12),
            tuple("AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO_SIDE", 13),
            tuple("AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE", 14),
            tuple("AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE_BACK", 15),
            tuple("AUDIO_CHANNEL_LAYOUT_SEVEN_POINT_ONE_WIDE", 16));
  }

  @Test
  @DisplayName(
      "Should keep the field numbers and types that workers build against when publishing probe version 7's audio facts")
  void
      shouldKeepTheFieldNumbersAndTypesThatWorkersBuildAgainstWhenPublishingProbeVersion7AudioFacts() {
    var stream = ProbeStreamInfo.getDescriptor();

    assertThat(
            Stream.of(
                    "bit_depth",
                    "sample_format",
                    "is_comment",
                    "is_visual_impaired",
                    "is_descriptions",
                    "is_original",
                    "is_dub",
                    "is_clean_effects",
                    "is_dependent",
                    "flac_minimum_block_size_samples")
                .map(stream::findFieldByName))
        .extracting(
            FieldDescriptor::getName,
            FieldDescriptor::getNumber,
            FieldDescriptor::getType,
            FieldDescriptor::hasPresence)
        .containsExactly(
            tuple("bit_depth", 13, Type.UINT32, true),
            tuple("sample_format", 39, Type.STRING, true),
            tuple("is_comment", 40, Type.BOOL, true),
            tuple("is_visual_impaired", 41, Type.BOOL, true),
            tuple("is_descriptions", 42, Type.BOOL, true),
            tuple("is_original", 43, Type.BOOL, true),
            tuple("is_dub", 44, Type.BOOL, true),
            tuple("is_clean_effects", 45, Type.BOOL, true),
            tuple("is_dependent", 46, Type.BOOL, true),
            tuple("flac_minimum_block_size_samples", 47, Type.UINT32, true));
  }

  @Test
  @DisplayName(
      "Should name a 7- or 8-channel layout with its channel count when a job encodes lossless or Opus audio")
  void shouldNameSevenOrEightChannelLayoutWithItsChannelCountWhenJobEncodesLosslessOrOpusAudio()
      throws Exception {
    var flacSixPointOne =
        audioEncode()
            .setCodec("flac")
            .setChannels(7)
            .setChannelLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE)
            .build();
    var alacSixPointOneBack =
        audioEncode()
            .setCodec("alac")
            .setChannels(7)
            .setChannelLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE_BACK)
            .build();
    var alacSevenPointOneWide =
        audioEncode()
            .setCodec("alac")
            .setChannels(8)
            .setChannelLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_SEVEN_POINT_ONE_WIDE)
            .build();

    var decodedFlac = AudioDecision.parseFrom(flacSixPointOne.toByteArray());
    var decodedAlac = AudioDecision.parseFrom(alacSixPointOneBack.toByteArray());
    var decodedAlacWide = AudioDecision.parseFrom(alacSevenPointOneWide.toByteArray());

    assertThat(decodedFlac.getChannelLayout())
        .isEqualTo(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE);
    assertThat(decodedFlac.getChannels()).isEqualTo(7);
    assertThat(decodedAlac.getChannelLayout())
        .isEqualTo(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE_BACK);
    assertThat(decodedAlac.getChannels()).isEqualTo(7);
    assertThat(decodedAlacWide.getChannelLayout())
        .isEqualTo(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_SEVEN_POINT_ONE_WIDE);
    assertThat(decodedAlacWide.getChannels()).isEqualTo(8);
  }

  @Test
  @DisplayName(
      "Should name each encoder's 5.0 layout with five channels when a job encodes audio under a channel maximum of 5")
  void shouldNameEachEncoders50LayoutWithFiveChannelsWhenJobEncodesAudioUnderChannelMaximumOf5()
      throws Exception {
    var aacEncode =
        audioEncode()
            .setCodec("aac")
            .setChannels(5)
            .setChannelLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO)
            .build();
    var ac3Encode =
        audioEncode()
            .setCodec("ac3")
            .setChannels(5)
            .setChannelLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO_SIDE)
            .build();

    var decodedAac = AudioDecision.parseFrom(aacEncode.toByteArray());
    var decodedAc3 = AudioDecision.parseFrom(ac3Encode.toByteArray());

    assertThat(decodedAac.getChannelLayout())
        .isEqualTo(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO);
    assertThat(decodedAac.getChannels()).isEqualTo(5);
    assertThat(decodedAc3.getChannelLayout())
        .isEqualTo(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO_SIDE);
    assertThat(decodedAc3.getChannels()).isEqualTo(5);
  }

  @Test
  @DisplayName(
      "Should document each layout's FFmpeg name and channel order when publishing the channel layout contract")
  void shouldDocumentEachLayoutsFfmpegNameAndChannelOrderWhenPublishingChannelLayoutContract()
      throws Exception {
    var proto = Files.readString(WORKER_SESSION_PROTO);

    var documented =
        DOCUMENTED_LAYOUT
            .matcher(proto)
            .results()
            .collect(
                Collectors.toMap(
                    match -> AudioChannelLayout.valueOf(match.group(3)),
                    match -> match.group(1) + ": " + match.group(2)));

    // ffmpeg -layouts of FFmpeg 8.1.3 in the pinned worker image lists these names and orders.
    assertThat(documented)
        .containsExactlyInAnyOrderEntriesOf(
            Map.ofEntries(
                Map.entry(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_MONO, "mono: FC"),
                Map.entry(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_STEREO, "stereo: FL+FR"),
                Map.entry(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_TWO_POINT_ONE, "2.1: FL+FR+LFE"),
                Map.entry(
                    AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_THREE_POINT_ZERO, "3.0: FL+FR+FC"),
                Map.entry(
                    AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_THREE_POINT_ZERO_BACK,
                    "3.0(back): FL+FR+BC"),
                Map.entry(
                    AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FOUR_POINT_ZERO, "4.0: FL+FR+FC+BC"),
                Map.entry(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_QUAD, "quad: FL+FR+BL+BR"),
                Map.entry(
                    AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_QUAD_SIDE, "quad(side): FL+FR+SL+SR"),
                Map.entry(
                    AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO, "5.0: FL+FR+FC+BL+BR"),
                Map.entry(
                    AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO_SIDE,
                    "5.0(side): FL+FR+FC+SL+SR"),
                Map.entry(
                    AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE,
                    "5.1: FL+FR+FC+LFE+BL+BR"),
                Map.entry(
                    AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE_SIDE,
                    "5.1(side): FL+FR+FC+LFE+SL+SR"),
                Map.entry(
                    AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE,
                    "6.1: FL+FR+FC+LFE+BC+SL+SR"),
                Map.entry(
                    AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE_BACK,
                    "6.1(back): FL+FR+FC+LFE+BL+BR+BC"),
                Map.entry(
                    AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_SEVEN_POINT_ONE,
                    "7.1: FL+FR+FC+LFE+BL+BR+SL+SR"),
                Map.entry(
                    AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_SEVEN_POINT_ONE_WIDE,
                    "7.1(wide): FL+FR+FC+LFE+BL+BR+FLC+FRC")));
  }

  @Test
  @DisplayName(
      "Should carry the output sample rate and bit depth when a job resamples hi-res lossless audio to 48 kHz")
  void shouldCarryOutputSampleRateAndBitDepthWhenJobResamplesHiResLosslessAudioTo48Khz()
      throws Exception {
    var flacStep =
        audioEncode()
            .setCodec("flac")
            .setChannels(6)
            .setChannelLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE)
            .setOutputSampleRateHz(48_000)
            .setOutputBitDepth(24)
            .build();

    var decoded = AudioDecision.parseFrom(flacStep.toByteArray());

    assertThat(decoded.getOutputSampleRateHz()).isEqualTo(48_000);
    assertThat(decoded.getOutputBitDepth()).isEqualTo(24);
  }

  @Test
  @DisplayName(
      "Should leave the output sample rate and bit depth absent when an older server sends an audio encode")
  void shouldLeaveOutputSampleRateAndBitDepthAbsentWhenOlderServerSendsAudioEncode()
      throws Exception {
    var olderServerEncode = audioEncode().setCodec("flac").setChannels(2).build();

    var decoded = AudioDecision.parseFrom(olderServerEncode.toByteArray());

    assertThat(decoded.hasOutputSampleRateHz()).isFalse();
    assertThat(decoded.hasOutputBitDepth()).isFalse();
  }

  @Test
  @DisplayName(
      "Should keep the field numbers and types that workers build against when publishing the output sample rate and bit depth")
  void
      shouldKeepFieldNumbersAndTypesThatWorkersBuildAgainstWhenPublishingOutputSampleRateAndBitDepth() {
    var audio = AudioDecision.getDescriptor();

    assertThat(Stream.of("output_sample_rate_hz", "output_bit_depth").map(audio::findFieldByName))
        .extracting(
            FieldDescriptor::getName,
            FieldDescriptor::getNumber,
            FieldDescriptor::getType,
            FieldDescriptor::hasPresence)
        .containsExactly(
            tuple("output_sample_rate_hz", 8, Type.UINT32, true),
            tuple("output_bit_depth", 9, Type.UINT32, true));
  }

  @Test
  @DisplayName(
      "Should carry every job attempt to pause and the hold limit when the server sends a seek's yield command")
  void shouldCarryEveryJobAttemptToPauseAndTheHoldLimitWhenTheServerSendsSeeksYieldCommand()
      throws Exception {
    var steady = uuid(UUID.randomUUID());
    var otherSteady = uuid(UUID.randomUUID());
    var holdLimit = Duration.newBuilder().setSeconds(6).build();
    var command =
        EstablishWorkerSessionResponse.newBuilder()
            .setYieldToSeek(
                YieldToSeekCommand.newBuilder()
                    .setTarget(workerIdentity())
                    .addJobAttemptIds(steady)
                    .addJobAttemptIds(otherSteady)
                    .setHoldLimit(holdLimit))
            .build();

    var parsed = EstablishWorkerSessionResponse.parseFrom(command.toByteArray());

    assertThat(parsed.getCommandCase())
        .isEqualTo(EstablishWorkerSessionResponse.CommandCase.YIELD_TO_SEEK);
    assertThat(parsed.getYieldToSeek().getTarget()).isEqualTo(workerIdentity());
    assertThat(parsed.getYieldToSeek().getJobAttemptIdsList()).containsExactly(steady, otherSteady);
    assertThat(parsed.getYieldToSeek().getHoldLimit()).isEqualTo(holdLimit);
  }

  @Test
  @DisplayName(
      "Should carry an empty list that resumes every paused job attempt when the seek's first segment is delivered")
  void shouldCarryAnEmptyListThatResumesEveryPausedJobAttemptWhenTheSeeksFirstSegmentIsDelivered()
      throws Exception {
    var ending = YieldToSeekCommand.newBuilder().setTarget(workerIdentity()).build();

    var parsed = YieldToSeekCommand.parseFrom(ending.toByteArray());

    assertThat(parsed.getJobAttemptIdsList()).isEmpty();
    assertThat(parsed.hasHoldLimit()).isFalse();
  }

  @Test
  @DisplayName(
      "Should keep the field numbers and types that workers build against when publishing the yield command")
  void shouldKeepTheFieldNumbersAndTypesThatWorkersBuildAgainstWhenPublishingTheYieldCommand() {
    var command = EstablishWorkerSessionResponse.getDescriptor().findFieldByName("yield_to_seek");
    var yieldToSeek = YieldToSeekCommand.getDescriptor();

    assertThat(command.getNumber()).isEqualTo(6);
    assertThat(command.getContainingOneof().getName()).isEqualTo("command");
    assertThat(command.getMessageType()).isEqualTo(yieldToSeek);
    assertThat(
            Stream.of("target", "job_attempt_ids", "hold_limit").map(yieldToSeek::findFieldByName))
        .extracting(
            FieldDescriptor::getName,
            FieldDescriptor::getNumber,
            FieldDescriptor::isRepeated,
            field -> field.getMessageType().getFullName())
        .containsExactly(
            tuple("target", 1, false, WorkerIdentity.getDescriptor().getFullName()),
            tuple("job_attempt_ids", 2, true, Uuid.getDescriptor().getFullName()),
            tuple("hold_limit", 3, false, Duration.getDescriptor().getFullName()));
  }

  @Test
  @DisplayName(
      "Should replace the offered encoders and keep a software encoder beside a hardware one when a worker's hardware encoder fails")
  void shouldReplaceTheOfferedEncodersAndKeepTheSoftwareEncoderWhenWorkersHardwareEncoderFails()
      throws Exception {
    var nvenc =
        WorkerVerifiedVideoEncoder.newBuilder()
            .setBackend(
                encoderBackend(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "h264_nvenc")
                    .setKind(EncoderKind.ENCODER_KIND_HARDWARE))
            .setKeyframeVerified(true)
            .build();
    var libx264 =
        WorkerVerifiedVideoEncoder.newBuilder()
            .setBackend(
                encoderBackend(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")
                    .setKind(EncoderKind.ENCODER_KIND_SOFTWARE))
            .setKeyframeVerified(true)
            .build();
    var registered =
        WorkerCapabilities.newBuilder()
            .addVideoEncoders(nvenc)
            .addSoftwareVideoEncoders(libx264)
            .build();
    var withdrawn =
        EstablishWorkerSessionRequest.newBuilder()
            .setVideoEncodersChanged(VideoEncodersChanged.newBuilder().addVideoEncoders(libx264))
            .build();
    var failed =
        JobAttemptFailed.newBuilder()
            .setJobAttemptId(uuid(UUID.randomUUID()))
            .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_HARDWARE_ENCODER_FAILED)
            .build();

    var parsedRegistration = WorkerCapabilities.parseFrom(registered.toByteArray());
    var parsedChange = EstablishWorkerSessionRequest.parseFrom(withdrawn.toByteArray());
    var parsedFailure = JobAttemptFailed.parseFrom(failed.toByteArray());

    assertThat(parsedRegistration.getVideoEncodersList()).containsExactly(nvenc);
    assertThat(parsedRegistration.getSoftwareVideoEncodersList()).containsExactly(libx264);
    assertThat(parsedChange.getVideoEncodersChanged().getVideoEncodersList())
        .containsExactly(libx264);
    assertThat(parsedFailure.getFailure())
        .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_HARDWARE_ENCODER_FAILED);
  }

  @Test
  @DisplayName("Should encode with the offered encoder when a job does not ask for software")
  void shouldEncodeWithTheOfferedEncoderWhenJobDoesNotAskForSoftware() throws Exception {
    var offered =
        VideoDecision.newBuilder().setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264);
    var software = offered.clone().setSoftwareEncoder(true);

    var parsedOffered = VideoDecision.parseFrom(offered.build().toByteArray());
    var parsedSoftware = VideoDecision.parseFrom(software.build().toByteArray());

    assertThat(parsedOffered.getSoftwareEncoder()).isFalse();
    assertThat(parsedSoftware.getSoftwareEncoder()).isTrue();
  }

  @Test
  @DisplayName(
      "Should carry every held-back job attempt with its release time when the server sends a hold-back command")
  void shouldCarryEveryHeldBackJobAttemptWithItsReleaseTimeWhenTheServerSendsAHoldBackCommand()
      throws Exception {
    var ahead = uuid(UUID.randomUUID());
    var fartherAhead = uuid(UUID.randomUUID());
    var command =
        EstablishWorkerSessionResponse.newBuilder()
            .setHoldBack(
                HoldBackCommand.newBuilder()
                    .setTarget(workerIdentity())
                    .addJobAttempts(heldBack(ahead, 12))
                    .addJobAttempts(heldBack(fartherAhead, 40)))
            .build();

    var parsed = EstablishWorkerSessionResponse.parseFrom(command.toByteArray());

    assertThat(parsed.getCommandCase())
        .isEqualTo(EstablishWorkerSessionResponse.CommandCase.HOLD_BACK);
    assertThat(parsed.getHoldBack().getTarget()).isEqualTo(workerIdentity());
    assertThat(parsed.getHoldBack().getJobAttemptsList())
        .containsExactly(heldBack(ahead, 12), heldBack(fartherAhead, 40));
  }

  @Test
  @DisplayName(
      "Should keep the field numbers and types that workers build against when publishing the hardware encoder failure")
  void shouldKeepTheFieldNumbersAndTypesThatWorkersBuildAgainstWhenPublishingHardwareFailure() {
    var softwareEncoders =
        WorkerCapabilities.getDescriptor().findFieldByName("software_video_encoders");
    var softwareEncoder = VideoDecision.getDescriptor().findFieldByName("software_encoder");
    var changed =
        EstablishWorkerSessionRequest.getDescriptor().findFieldByName("video_encoders_changed");
    var changedEncoders = VideoEncodersChanged.getDescriptor().findFieldByName("video_encoders");

    assertThat(softwareEncoders.getNumber()).isEqualTo(7);
    assertThat(softwareEncoders.isRepeated()).isTrue();
    assertThat(softwareEncoders.getMessageType())
        .isEqualTo(WorkerVerifiedVideoEncoder.getDescriptor());
    assertThat(softwareEncoder.getNumber()).isEqualTo(19);
    assertThat(softwareEncoder.getType()).isEqualTo(Type.BOOL);
    assertThat(changed.getNumber()).isEqualTo(11);
    assertThat(changed.getMessageType()).isEqualTo(VideoEncodersChanged.getDescriptor());
    assertThat(changedEncoders.getNumber()).isEqualTo(1);
    assertThat(changedEncoders.isRepeated()).isTrue();
    assertThat(changedEncoders.getMessageType())
        .isEqualTo(WorkerVerifiedVideoEncoder.getDescriptor());
    assertThat(JobAttemptFailure.JOB_ATTEMPT_FAILURE_HARDWARE_ENCODER_FAILED.getNumber())
        .isEqualTo(9);
  }

  @Test
  @DisplayName(
      "Should keep the field numbers and types that workers build against when publishing the hold-back command")
  void shouldKeepTheFieldNumbersAndTypesThatWorkersBuildAgainstWhenPublishingTheHoldBackCommand() {
    var command = EstablishWorkerSessionResponse.getDescriptor().findFieldByName("hold_back");
    var holdBack = HoldBackCommand.getDescriptor();
    var heldBack = HeldBackJobAttempt.getDescriptor();

    assertThat(command.getNumber()).isEqualTo(7);
    assertThat(command.getContainingOneof().getName()).isEqualTo("command");
    assertThat(command.getMessageType()).isEqualTo(holdBack);
    assertThat(
            Stream.concat(
                Stream.of("target", "job_attempts").map(holdBack::findFieldByName),
                Stream.of("job_attempt_id", "release_after").map(heldBack::findFieldByName)))
        .extracting(
            FieldDescriptor::getName,
            FieldDescriptor::getNumber,
            FieldDescriptor::isRepeated,
            field -> field.getMessageType().getFullName())
        .containsExactly(
            tuple("target", 1, false, WorkerIdentity.getDescriptor().getFullName()),
            tuple("job_attempts", 2, true, heldBack.getFullName()),
            tuple("job_attempt_id", 1, false, Uuid.getDescriptor().getFullName()),
            tuple("release_after", 2, false, Duration.getDescriptor().getFullName()));
  }

  private static HeldBackJobAttempt heldBack(Uuid jobAttemptId, long releaseAfterSeconds) {
    return HeldBackJobAttempt.newBuilder()
        .setJobAttemptId(jobAttemptId)
        .setReleaseAfter(Duration.newBuilder().setSeconds(releaseAfterSeconds))
        .build();
  }

  private AudioDecision.Builder audioEncode() {
    return AudioDecision.newBuilder().setMode(AudioMode.AUDIO_MODE_TRANSCODE);
  }
}
