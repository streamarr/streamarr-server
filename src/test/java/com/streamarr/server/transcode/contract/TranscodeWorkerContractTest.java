package com.streamarr.server.transcode.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.Descriptor;
import com.streamarr.transcode.v1.JobAttemptStarted;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.RenditionJob;
import com.streamarr.transcode.v1.SegmentContentType;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.StartRenditionCommand;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionRequest;
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
  @DisplayName("Should expose one reverse worker session and one segment upload stream")
  void shouldExposeOneReverseWorkerSessionAndOneSegmentUploadStream() {
    var service = TranscodeWorkerServiceGrpc.getServiceDescriptor();

    assertThat(service.getMethods()).hasSize(2);
    assertThat(TranscodeWorkerServiceGrpc.getWorkerSessionMethod().getType())
        .isEqualTo(MethodType.BIDI_STREAMING);
    assertThat(TranscodeWorkerServiceGrpc.getUploadSegmentMethod().getType())
        .isEqualTo(MethodType.CLIENT_STREAMING);
  }

  @Test
  @DisplayName("Should carry self-reported worker identity and distinct job attempt identity")
  void shouldCarrySelfReportedWorkerIdentityAndDistinctJobAttemptIdentity() throws Exception {
    var sourceNamespaceId = UUID.randomUUID();
    var registration =
        WorkerSessionRequest.newBuilder()
            .setRegistration(
                WorkerRegistration.newBuilder()
                    .setWorker(workerIdentity())
                    .setCapabilities(
                        WorkerCapabilities.newBuilder()
                            .addSourceNamespaceIds(uuid(sourceNamespaceId)))
                    .setAvailableSlots(1))
            .build();

    var parsedRegistration = WorkerSessionRequest.parseFrom(registration.toByteArray());

    assertThat(parsedRegistration.getRegistration().getWorker()).isEqualTo(workerIdentity());
    assertThat(parsedRegistration.getRegistration().getCapabilities().getSourceNamespaceIdsList())
        .containsExactly(uuid(sourceNamespaceId));

    var streamSessionId = UUID.randomUUID();
    var jobId = UUID.randomUUID();
    var jobAttemptId = UUID.randomUUID();
    var start =
        StartRenditionCommand.newBuilder()
            .setTarget(workerIdentity())
            .setJob(
                RenditionJob.newBuilder()
                    .setStreamSessionId(uuid(streamSessionId))
                    .setJobId(uuid(jobId))
                    .setJobAttemptId(uuid(jobAttemptId))
                    .setSource(
                        MediaSourceRef.newBuilder()
                            .setSourceNamespaceId(uuid(sourceNamespaceId))
                            .setRelativeKey("movies/example.mkv")))
            .build();

    var parsedStart = StartRenditionCommand.parseFrom(start.toByteArray());

    assertThat(parsedStart.getJob().getStreamSessionId()).isEqualTo(uuid(streamSessionId));
    assertThat(parsedStart.getJob().getJobId()).isEqualTo(uuid(jobId));
    assertThat(parsedStart.getJob().getJobAttemptId()).isEqualTo(uuid(jobAttemptId));
  }

  @Test
  @DisplayName("Should preserve opaque media source keys byte for byte")
  void shouldPreserveOpaqueMediaSourceKeysByteForByte() throws Exception {
    var source =
        MediaSourceRef.newBuilder()
            .setSourceNamespaceId(uuid(UUID.randomUUID()))
            .setRelativeKey("../feature%2Ffilm.mkv")
            .build();

    assertThat(MediaSourceRef.parseFrom(source.toByteArray()).getRelativeKey())
        .isEqualTo("../feature%2Ffilm.mkv");
  }

  @Test
  @DisplayName("Should upload segment bytes outside the worker control stream")
  void shouldUploadSegmentBytesOutsideTheWorkerControlStream() throws Exception {
    var attemptId = uuid(UUID.randomUUID());
    var metadata =
        SegmentUploadMetadata.newBuilder()
            .setWorkerSessionId(uuid(UUID.randomUUID()))
            .setWorker(workerIdentity())
            .setStreamSessionId(uuid(UUID.randomUUID()))
            .setJobId(uuid(UUID.randomUUID()))
            .setJobAttemptId(attemptId)
            .setRenditionName("720p")
            .setSegmentName("segment0.ts")
            .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP2T)
            .setContentLengthBytes(4)
            .build();
    var request = UploadSegmentRequest.newBuilder().setMetadata(metadata).build();

    var parsed = UploadSegmentRequest.parseFrom(request.toByteArray());

    assertThat(parsed.getMetadata().getJobAttemptId()).isEqualTo(attemptId);
    assertThat(parsed.getMetadata().getSegmentName()).isEqualTo("segment0.ts");
  }

  @Test
  @DisplayName("Should keep the wire vocabulary narrow and secret free")
  void shouldKeepTheWireVocabularyNarrowAndSecretFree() {
    var fields = new HashSet<String>();
    collectFields(WorkerSessionRequest.getDescriptor(), fields);
    collectFields(WorkerRegistration.getDescriptor(), fields);
    collectFields(StartRenditionCommand.getDescriptor(), fields);
    collectFields(RenditionJob.getDescriptor(), fields);
    collectFields(JobAttemptStarted.getDescriptor(), fields);
    collectFields(UploadSegmentRequest.getDescriptor(), fields);

    assertThat(fields)
        .contains("worker", "target", "stream_session_id", "job_id", "job_attempt_id")
        .doesNotContain(
            "session_id",
            "source_path",
            "command",
            "command_line",
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

  private Uuid uuid(UUID value) {
    return Uuid.newBuilder()
        .setMostSignificantBits(value.getMostSignificantBits())
        .setLeastSignificantBits(value.getLeastSignificantBits())
        .build();
  }
}
