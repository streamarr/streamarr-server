package com.streamarr.transcode.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import com.google.protobuf.Timestamp;
import com.streamarr.transcode.v1.AudioDecision;
import com.streamarr.transcode.v1.AudioMode;
import com.streamarr.transcode.v1.CertificateBundle;
import com.streamarr.transcode.v1.ContainerFormat;
import com.streamarr.transcode.v1.DrainState;
import com.streamarr.transcode.v1.EnrollWorkerRejection;
import com.streamarr.transcode.v1.EnrollWorkerRequest;
import com.streamarr.transcode.v1.EnrollWorkerResponse;
import com.streamarr.transcode.v1.HeartbeatRequest;
import com.streamarr.transcode.v1.HeartbeatResponse;
import com.streamarr.transcode.v1.InitializationSegment;
import com.streamarr.transcode.v1.InspectJobRejection;
import com.streamarr.transcode.v1.InspectJobRequest;
import com.streamarr.transcode.v1.InspectJobResponse;
import com.streamarr.transcode.v1.JobSnapshot;
import com.streamarr.transcode.v1.ListJobsRequest;
import com.streamarr.transcode.v1.ListJobsResponse;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProtocolRange;
import com.streamarr.transcode.v1.ProtocolRevision;
import com.streamarr.transcode.v1.ProveEndpointRequest;
import com.streamarr.transcode.v1.ProveEndpointResponse;
import com.streamarr.transcode.v1.PublicTrustBundle;
import com.streamarr.transcode.v1.ReadSegmentRequest;
import com.streamarr.transcode.v1.ReadSegmentResponse;
import com.streamarr.transcode.v1.RegisterWorkerAccepted;
import com.streamarr.transcode.v1.RegisterWorkerRejection;
import com.streamarr.transcode.v1.RegisterWorkerRequest;
import com.streamarr.transcode.v1.RegisterWorkerResponse;
import com.streamarr.transcode.v1.RenditionObservation;
import com.streamarr.transcode.v1.RenditionSpec;
import com.streamarr.transcode.v1.RenditionState;
import com.streamarr.transcode.v1.RevocationUpdate;
import com.streamarr.transcode.v1.RevokedCertificate;
import com.streamarr.transcode.v1.RotateWorkerCertificateRejection;
import com.streamarr.transcode.v1.RotateWorkerCertificateRequest;
import com.streamarr.transcode.v1.RotateWorkerCertificateResponse;
import com.streamarr.transcode.v1.SegmentContentType;
import com.streamarr.transcode.v1.SegmentMetadata;
import com.streamarr.transcode.v1.SegmentRef;
import com.streamarr.transcode.v1.SignedRevocationUpdate;
import com.streamarr.transcode.v1.StartJobRejection;
import com.streamarr.transcode.v1.StartJobRequest;
import com.streamarr.transcode.v1.StartJobResponse;
import com.streamarr.transcode.v1.StopJobRejection;
import com.streamarr.transcode.v1.StopJobRequest;
import com.streamarr.transcode.v1.StopJobResponse;
import com.streamarr.transcode.v1.SubtitleDecision;
import com.streamarr.transcode.v1.SubtitleMode;
import com.streamarr.transcode.v1.TranscodeDecision;
import com.streamarr.transcode.v1.TranscodeExecutionParameters;
import com.streamarr.transcode.v1.TranscodeJobObservation;
import com.streamarr.transcode.v1.TranscodeJobRef;
import com.streamarr.transcode.v1.TranscodeJobSpec;
import com.streamarr.transcode.v1.TranscodeJobState;
import com.streamarr.transcode.v1.TranscodeMode;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerCapacity;
import com.streamarr.transcode.v1.WorkerEndpoint;
import com.streamarr.transcode.v1.WorkerTarget;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class ContractRoundTripTest {

  @Test
  @DisplayName("Should round trip a complete typed transcode job specification")
  void shouldRoundTripCompleteTypedTranscodeJobSpecification() throws Exception {
    var jobSpec = completeJobSpec();

    assertRoundTrips(jobSpec, TranscodeJobSpec.parser());
    assertThat(jobSpec.getDecision().hasAudio()).isTrue();
    assertThat(jobSpec.getDecision().getAudio().hasCodec()).isTrue();
    assertThat(jobSpec.getDecision().getSubtitle().hasStreamIndex()).isTrue();
    assertThat(jobSpec.getRenditionsList())
        .extracting(RenditionSpec::getLabel)
        .containsExactly("1080p", "720p");
  }

  @Test
  @DisplayName("Should round trip every command result without losing its oneof case")
  void shouldRoundTripEveryCommandResultWithoutLosingOneofCase() throws Exception {
    var jobRef = jobRef();
    var observation = observation();

    var startResults =
        List.of(
            StartJobResponse.newBuilder().setAccepted(observation).build(),
            StartJobResponse.newBuilder()
                .setRejection(StartJobRejection.START_JOB_REJECTION_CAPACITY_EXHAUSTED)
                .build(),
            StartJobResponse.newBuilder().setCleanupPending(jobRef).build());
    var stopResults =
        List.of(
            StopJobResponse.newBuilder().setStopped(jobRef).build(),
            StopJobResponse.newBuilder().setAlreadyAbsent(jobRef).build(),
            StopJobResponse.newBuilder().setCleanupPending(jobRef).build(),
            StopJobResponse.newBuilder()
                .setRejection(StopJobRejection.STOP_JOB_REJECTION_STALE_GENERATION)
                .build());
    var inspectResults =
        List.of(
            InspectJobResponse.newBuilder().setObservation(observation).build(),
            InspectJobResponse.newBuilder().setCleanupPending(jobRef).build(),
            InspectJobResponse.newBuilder()
                .setRejection(InspectJobRejection.INSPECT_JOB_REJECTION_TARGET_MISMATCH)
                .build());

    for (var response : startResults) {
      assertRoundTrips(response, StartJobResponse.parser());
    }
    for (var response : stopResults) {
      assertRoundTrips(response, StopJobResponse.parser());
    }
    for (var response : inspectResults) {
      assertRoundTrips(response, InspectJobResponse.parser());
    }

    var replaced =
        StartJobResponse.newBuilder()
            .setAccepted(observation)
            .setRejection(StartJobRejection.START_JOB_REJECTION_JOB_CONFLICT)
            .build();
    assertThat(replaced.hasAccepted()).isFalse();
    assertThat(replaced.getResultCase()).isEqualTo(StartJobResponse.ResultCase.REJECTION);
  }

  @Test
  @DisplayName("Should round trip every targeted worker control request")
  void shouldRoundTripEveryTargetedWorkerControlRequest() throws Exception {
    var nonce = ByteString.copyFrom(new byte[32]);
    var proofRequest =
        ProveEndpointRequest.newBuilder().setTarget(target()).setNonce(nonce).build();
    var proofResponse =
        ProveEndpointResponse.newBuilder()
            .setTarget(target())
            .setNonce(nonce)
            .setLeafCertificateDer(ByteString.copyFromUtf8("leaf"))
            .build();
    var start =
        StartJobRequest.newBuilder()
            .setCommandId(uuid(51, 52))
            .setTarget(target())
            .setJobSpec(completeJobSpec())
            .build();
    var stop =
        StopJobRequest.newBuilder()
            .setCommandId(uuid(53, 54))
            .setTarget(target())
            .setJobRef(jobRef())
            .build();
    var inspect = InspectJobRequest.newBuilder().setTarget(target()).setJobRef(jobRef()).build();
    var list =
        ListJobsRequest.newBuilder()
            .setTarget(target())
            .setPageSize(256)
            .setContinuation(ByteString.copyFromUtf8("page"))
            .build();
    var read =
        ReadSegmentRequest.newBuilder()
            .setTarget(target())
            .setJobRef(jobRef())
            .setRenditionLabel("1080p")
            .setSegment(SegmentRef.newBuilder().setMediaSequence(37))
            .build();

    assertRoundTrips(proofRequest, ProveEndpointRequest.parser());
    assertRoundTrips(proofResponse, ProveEndpointResponse.parser());
    assertRoundTrips(start, StartJobRequest.parser());
    assertRoundTrips(stop, StopJobRequest.parser());
    assertRoundTrips(inspect, InspectJobRequest.parser());
    assertRoundTrips(list, ListJobsRequest.parser());
    assertRoundTrips(read, ReadSegmentRequest.parser());
  }

  @Test
  @DisplayName("Should round trip worker observation enrollment and revocation state")
  void shouldRoundTripWorkerObservationEnrollmentAndRevocationState() throws Exception {
    var revocation = signedRevocationUpdate();
    var capabilities =
        WorkerCapabilities.newBuilder()
            .addVideoCodecFamilies("h264")
            .addEncoders("h264_nvenc")
            .addHardwareAccelerators("cuda")
            .addSourceNamespaceIds(uuid(31, 32))
            .addContainerFormats(ContainerFormat.CONTAINER_FORMAT_FMP4)
            .build();
    var heartbeat =
        HeartbeatRequest.newBuilder()
            .setTarget(target())
            .setBindingEpoch(9)
            .setSequence(11)
            .setProtocolRevision(1)
            .setCapabilities(capabilities)
            .setDrainState(DrainState.DRAIN_STATE_ACCEPTING)
            .setCapacity(WorkerCapacity.newBuilder().setTotalSlots(8).setOccupiedSlots(3))
            .addJobs(observation())
            .build();
    var heartbeatResponse =
        HeartbeatResponse.newBuilder()
            .setAcceptedSequence(11)
            .setBindingEpoch(9)
            .setRevocationUpdate(revocation)
            .build();
    var registrationRequest =
        RegisterWorkerRequest.newBuilder()
            .setTarget(target())
            .setEndpoint(endpoint())
            .setSupportedProtocol(protocolRange())
            .build();
    var registration =
        RegisterWorkerResponse.newBuilder()
            .setAccepted(
                RegisterWorkerAccepted.newBuilder()
                    .setBindingEpoch(9)
                    .setProtocolRevision(1)
                    .setHeartbeatInterval(Duration.newBuilder().setSeconds(15))
                    .setRevocationUpdate(revocation))
            .build();
    var rejectedRegistration =
        RegisterWorkerResponse.newBuilder()
            .setRejection(RegisterWorkerRejection.REGISTER_WORKER_REJECTION_ACTIVE_BINDING)
            .build();
    var enrollmentRequest =
        EnrollWorkerRequest.newBuilder()
            .setRequestId(uuid(41, 42))
            .setWorkerId(target().getWorkerId())
            .setCertificateSigningRequestDer(ByteString.copyFromUtf8("csr"))
            .build();
    var certificate = certificateBundle();
    var enrollment = EnrollWorkerResponse.newBuilder().setCertificate(certificate).build();
    var rejectedEnrollment =
        EnrollWorkerResponse.newBuilder()
            .setRejection(EnrollWorkerRejection.ENROLL_WORKER_REJECTION_INVALID_OR_EXPIRED_GRANT)
            .build();
    var rotationRequest =
        RotateWorkerCertificateRequest.newBuilder()
            .setRequestId(uuid(43, 44))
            .setWorkerId(target().getWorkerId())
            .setCertificateSigningRequestDer(ByteString.copyFromUtf8("rotated-csr"))
            .build();
    var rotation = RotateWorkerCertificateResponse.newBuilder().setCertificate(certificate).build();
    var rejectedRotation =
        RotateWorkerCertificateResponse.newBuilder()
            .setRejection(
                RotateWorkerCertificateRejection
                    .ROTATE_WORKER_CERTIFICATE_REJECTION_IDENTITY_MISMATCH)
            .build();

    assertRoundTrips(heartbeat, HeartbeatRequest.parser());
    assertRoundTrips(heartbeatResponse, HeartbeatResponse.parser());
    assertRoundTrips(registrationRequest, RegisterWorkerRequest.parser());
    assertRoundTrips(registration, RegisterWorkerResponse.parser());
    assertRoundTrips(rejectedRegistration, RegisterWorkerResponse.parser());
    assertThat(ProtocolRevision.PROTOCOL_REVISION_V1_VALUE).isEqualTo(1);
    assertRoundTrips(enrollmentRequest, EnrollWorkerRequest.parser());
    assertRoundTrips(enrollment, EnrollWorkerResponse.parser());
    assertRoundTrips(rejectedEnrollment, EnrollWorkerResponse.parser());
    assertRoundTrips(rotationRequest, RotateWorkerCertificateRequest.parser());
    assertRoundTrips(rotation, RotateWorkerCertificateResponse.parser());
    assertRoundTrips(rejectedRotation, RotateWorkerCertificateResponse.parser());
  }

  @Test
  @DisplayName("Should preserve bounded segment variants and unknown enum values")
  void shouldPreserveBoundedSegmentVariantsAndUnknownEnumValues() throws Exception {
    var initialization =
        SegmentRef.newBuilder()
            .setInitialization(InitializationSegment.getDefaultInstance())
            .build();
    var media = SegmentRef.newBuilder().setMediaSequence(37).build();
    var metadata =
        ReadSegmentResponse.newBuilder()
            .setMetadata(
                SegmentMetadata.newBuilder()
                    .setContentLengthBytes(65_536)
                    .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP4))
            .build();
    var chunk =
        ReadSegmentResponse.newBuilder().setData(ByteString.copyFrom(new byte[65_536])).build();
    var unknown =
        TranscodeJobObservation.newBuilder().setJobRef(jobRef()).setStateValue(999).build();

    assertRoundTrips(initialization, SegmentRef.parser());
    assertRoundTrips(media, SegmentRef.parser());
    assertRoundTrips(metadata, ReadSegmentResponse.parser());
    assertRoundTrips(chunk, ReadSegmentResponse.parser());
    assertThat(chunk.getData()).hasSize(65_536);
    assertThat(metadata.getFrameCase()).isEqualTo(ReadSegmentResponse.FrameCase.METADATA);

    var replaced =
        ReadSegmentResponse.newBuilder()
            .setMetadata(metadata.getMetadata())
            .setData(ByteString.copyFromUtf8("data"))
            .build();
    assertThat(replaced.hasMetadata()).isFalse();
    assertThat(replaced.getFrameCase()).isEqualTo(ReadSegmentResponse.FrameCase.DATA);

    var decoded = TranscodeJobObservation.parseFrom(unknown.toByteArray());
    assertThat(decoded.getState()).isEqualTo(TranscodeJobState.UNRECOGNIZED);
    assertThat(decoded.getStateValue()).isEqualTo(999);
  }

  @Test
  @DisplayName("Should round trip list snapshots for observed and cleanup-pending jobs")
  void shouldRoundTripListSnapshotsForObservedAndCleanupPendingJobs() throws Exception {
    var response =
        ListJobsResponse.newBuilder()
            .addJobs(JobSnapshot.newBuilder().setObservation(observation()))
            .addJobs(JobSnapshot.newBuilder().setCleanupPending(jobRef()))
            .setNextContinuation(ByteString.copyFromUtf8("next"))
            .build();

    assertRoundTrips(response, ListJobsResponse.parser());
    assertThat(response.getJobs(0).getSnapshotCase())
        .isEqualTo(JobSnapshot.SnapshotCase.OBSERVATION);
    assertThat(response.getJobs(1).getSnapshotCase())
        .isEqualTo(JobSnapshot.SnapshotCase.CLEANUP_PENDING);
  }

  private static TranscodeJobSpec completeJobSpec() {
    var audio =
        AudioDecision.newBuilder()
            .setMode(AudioMode.AUDIO_MODE_TRANSCODE)
            .setCodec("aac")
            .setChannels(6)
            .setBitrateBitsPerSecond(384_000)
            .build();
    var subtitle =
        SubtitleDecision.newBuilder()
            .setMode(SubtitleMode.SUBTITLE_MODE_BURN_IN)
            .setCodec("ass")
            .setStreamIndex(2)
            .setLanguage("eng")
            .build();
    var decision =
        TranscodeDecision.newBuilder()
            .setTranscodeMode(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
            .setVideoCodecFamily("h264")
            .setAudio(audio)
            .setSubtitle(subtitle)
            .setContainer(ContainerFormat.CONTAINER_FORMAT_FMP4)
            .setForceKeyframesAtSegmentBoundaries(true)
            .build();
    var execution =
        TranscodeExecutionParameters.newBuilder()
            .setSeekPositionSeconds(12)
            .setSegmentDurationSeconds(6)
            .setFramerate(23.976)
            .setStartNumber(4)
            .setStartupTimeout(Duration.newBuilder().setSeconds(30))
            .build();

    return TranscodeJobSpec.newBuilder()
        .setSessionId(uuid(1, 2))
        .setJobRef(jobRef())
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(uuid(3, 4))
                .setRelativeKey("movies/example.mkv"))
        .setDecision(decision)
        .setExecution(execution)
        .addRenditions(
            renditionBuilder("1080p")
                .setWidth(1920)
                .setHeight(1080)
                .setBitrateBitsPerSecond(8_000_000))
        .addRenditions(
            renditionBuilder("720p")
                .setWidth(1280)
                .setHeight(720)
                .setBitrateBitsPerSecond(4_000_000))
        .build();
  }

  private static RenditionSpec.Builder renditionBuilder(String label) {
    return RenditionSpec.newBuilder().setLabel(label);
  }

  private static TranscodeJobObservation observation() {
    return TranscodeJobObservation.newBuilder()
        .setJobRef(jobRef())
        .setState(TranscodeJobState.TRANSCODE_JOB_STATE_RUNNING)
        .addRenditions(
            RenditionObservation.newBuilder()
                .setLabel("1080p")
                .setState(RenditionState.RENDITION_STATE_RUNNING))
        .addRenditions(
            RenditionObservation.newBuilder()
                .setLabel("720p")
                .setState(RenditionState.RENDITION_STATE_RUNNING))
        .build();
  }

  private static SignedRevocationUpdate signedRevocationUpdate() {
    var revoked =
        RevokedCertificate.newBuilder()
            .setIssuerCertificateSha256(ByteString.copyFrom(new byte[32]))
            .setSerialNumber(ByteString.copyFrom(new byte[] {1, 2, 3}))
            .setRevokedAt(Timestamp.newBuilder().setSeconds(1_786_000_000))
            .build();
    var update =
        RevocationUpdate.newBuilder()
            .setSequence(7)
            .setIssuedAt(Timestamp.newBuilder().setSeconds(1_786_000_000))
            .setExpiresAt(Timestamp.newBuilder().setSeconds(1_786_003_600))
            .addRevokedCertificates(revoked)
            .build();
    return SignedRevocationUpdate.newBuilder()
        .setSerializedUpdate(update.toByteString())
        .setSignature(ByteString.copyFromUtf8("signature"))
        .setSignerCertificateSha256(ByteString.copyFrom(new byte[32]))
        .build();
  }

  private static CertificateBundle certificateBundle() {
    var trustBundle =
        PublicTrustBundle.newBuilder()
            .setVersion(7)
            .addTrustAnchorCertificatesDer(ByteString.copyFromUtf8("root"))
            .addIssuerCertificatesDer(ByteString.copyFromUtf8("issuer"))
            .addRevocationSignerCertificatesDer(ByteString.copyFromUtf8("revocation-signer"))
            .build();
    return CertificateBundle.newBuilder()
        .setWorkerId(target().getWorkerId())
        .setLeafCertificateDer(ByteString.copyFromUtf8("leaf"))
        .setTrustBundle(trustBundle)
        .build();
  }

  private static WorkerTarget target() {
    return WorkerTarget.newBuilder().setWorkerId(uuid(11, 12)).setBootId(uuid(21, 22)).build();
  }

  private static TranscodeJobRef jobRef() {
    return TranscodeJobRef.newBuilder().setJobId(uuid(5, 6)).setGeneration(3).build();
  }

  private static Uuid uuid(long mostSignificantBits, long leastSignificantBits) {
    return Uuid.newBuilder()
        .setMostSignificantBits(mostSignificantBits)
        .setLeastSignificantBits(leastSignificantBits)
        .build();
  }

  private static <T extends Message> void assertRoundTrips(T message, Parser<T> parser)
      throws Exception {
    assertThat(parser.parseFrom(message.toByteArray())).isEqualTo(message);
  }

  private static WorkerEndpoint endpoint() {
    return WorkerEndpoint.newBuilder().setHost("worker.internal").setPort(8443).build();
  }

  private static ProtocolRange protocolRange() {
    return ProtocolRange.newBuilder().setMinimumRevision(1).setMaximumRevision(1).build();
  }
}
