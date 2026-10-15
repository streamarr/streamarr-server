package com.streamarr.server.transcode.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.Descriptor;
import com.streamarr.transcode.v1.CompatibleBase;
import com.streamarr.transcode.v1.DolbyVisionAbsent;
import com.streamarr.transcode.v1.EncoderKind;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.Fraction;
import com.streamarr.transcode.v1.JobAttemptStarted;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.Presence;
import com.streamarr.transcode.v1.SegmentContentType;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
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
import com.streamarr.transcode.v1.VideoMode;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerVerifiedVideoEncoder;
import io.grpc.MethodDescriptor.MethodType;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Transcode Worker Contract Tests")
class TranscodeWorkerContractTest {

  @Test
  @DisplayName(
      "Should expose one reverse worker session and one segment upload stream when validating the protocol")
  void shouldExposeOneReverseWorkerSessionAndOneSegmentUploadStreamWhenValidatingProtocol() {
    var service = TranscodeWorkerServiceGrpc.getServiceDescriptor();

    assertThat(service.getMethods()).hasSize(2);
    assertThat(TranscodeWorkerServiceGrpc.getEstablishWorkerSessionMethod().getType())
        .isEqualTo(MethodType.BIDI_STREAMING);
    assertThat(TranscodeWorkerServiceGrpc.getUploadSegmentMethod().getType())
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
}
