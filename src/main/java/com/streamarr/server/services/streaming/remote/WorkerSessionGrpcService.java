package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.fromProto;

import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.remote.LiveWorkerConnectionRegistry.ReportedJobAttemptEnd;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptCompleted;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptStarted;
import com.streamarr.transcode.v1.ProbeCapacityChanged;
import com.streamarr.transcode.v1.ReportProbeAttemptRequest;
import com.streamarr.transcode.v1.ReportProbeAttemptResponse;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VideoEncodersChanged;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class WorkerSessionGrpcService
    extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase {

  /** Receives frames after the service has already rejected an upload call. */
  private static final StreamObserver<UploadSegmentRequest> IGNORED_UPLOAD_OBSERVER =
      new StreamObserver<>() {
        @Override
        public void onNext(UploadSegmentRequest value) {
          // The response has already ended with an error.
        }

        @Override
        public void onError(Throwable throwable) {
          // The response has already ended with an error.
        }

        @Override
        public void onCompleted() {
          // The response has already ended with an error.
        }
      };

  private final LiveWorkerConnectionRegistry workerConnections;
  private final SegmentStore segmentStore;
  private final SegmentUploadAdmission segmentUploadAdmission;
  private final ProbeReportCalls probeReportCalls;

  WorkerSessionGrpcService(
      LiveWorkerConnectionRegistry workerConnections, SegmentStore segmentStore) {
    this.workerConnections = workerConnections;
    this.segmentStore = segmentStore;
    segmentUploadAdmission =
        new SegmentUploadAdmission(
            SegmentUploadLimits.MAXIMUM_CONCURRENT_SEGMENT_UPLOADS,
            SegmentUploadLimits.MAXIMUM_BUFFERED_SEGMENT_BYTES,
            SegmentUploadLimits.MAXIMUM_SEGMENT_UPLOADS_PER_WORKER);
    probeReportCalls = new ProbeReportCalls(workerConnections);
  }

  @Override
  public StreamObserver<EstablishWorkerSessionRequest> establishWorkerSession(
      StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
    var authenticatedWorkerId = WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID.get();
    return new RegistrationObserver(authenticatedWorkerId, responseObserver, workerConnections);
  }

  @Override
  public StreamObserver<UploadSegmentRequest> uploadSegment(
      StreamObserver<UploadSegmentResponse> responseObserver) {
    var authenticatedWorkerId = WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID.get();
    if (authenticatedWorkerId == null) {
      // The identity interceptor rejects before reaching here, so this means the server was wired
      // without it. Fail closed rather than admit an upload no per-worker allowance can bound.
      log.error("Rejecting segment upload with no authenticated worker identity in context");
      responseObserver.onError(
          Status.UNAUTHENTICATED
              .withDescription("Worker identity is required")
              .asRuntimeException());
      return IGNORED_UPLOAD_OBSERVER;
    }

    return switch (segmentUploadAdmission.tryAdmit(authenticatedWorkerId)) {
      case SegmentUploadAdmission.Admitted(var ticket) ->
          SegmentUploadObserver.builder()
              .authenticatedWorkerId(authenticatedWorkerId)
              .workerConnections(workerConnections)
              .segmentStore(segmentStore)
              .responseObserver(responseObserver)
              .uploadTicket(ticket)
              .build();
      case SegmentUploadAdmission.WorkerLimitReached _ -> {
        log.warn(
            "Rejecting segment upload from worker {}: its segment upload limit is reached",
            authenticatedWorkerId);
        responseObserver.onError(SegmentUploadLimits.workerLimitRefusal());
        yield IGNORED_UPLOAD_OBSERVER;
      }
      case SegmentUploadAdmission.FleetLimitReached _ -> {
        log.warn(
            "Rejecting segment upload from worker {}: concurrent upload limit reached",
            authenticatedWorkerId);
        responseObserver.onError(
            Status.RESOURCE_EXHAUSTED
                .withDescription("Concurrent segment upload limit reached")
                .asRuntimeException());
        yield IGNORED_UPLOAD_OBSERVER;
      }
    };
  }

  @Override
  public StreamObserver<ReportProbeAttemptRequest> reportProbeAttempt(
      StreamObserver<ReportProbeAttemptResponse> responseObserver) {
    return probeReportCalls.open(
        WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID.get(), responseObserver);
  }

  private static final class RegistrationObserver
      implements StreamObserver<EstablishWorkerSessionRequest> {

    private final UUID authenticatedWorkerId;
    private final StreamObserver<EstablishWorkerSessionResponse> responseObserver;
    private final LiveWorkerConnectionRegistry workerConnections;
    private boolean registered;
    private UUID workerSessionId;

    private RegistrationObserver(
        UUID authenticatedWorkerId,
        StreamObserver<EstablishWorkerSessionResponse> responseObserver,
        LiveWorkerConnectionRegistry workerConnections) {
      this.authenticatedWorkerId = authenticatedWorkerId;
      this.responseObserver = responseObserver;
      this.workerConnections = workerConnections;
    }

    @Override
    public void onNext(EstablishWorkerSessionRequest request) {
      if (authenticatedWorkerId == null) {
        reject(Status.UNAUTHENTICATED.withDescription("Worker identity is required"));
        return;
      }
      if (registered) {
        handleSessionEvent(request);
        return;
      }
      if (!request.hasRegistration() || !request.getRegistration().hasWorker()) {
        reject(
            Status.INVALID_ARGUMENT.withDescription("Worker session must begin with registration"));
        return;
      }

      var reportedWorkerId = fromProto(request.getRegistration().getWorker().getWorkerId());
      if (!authenticatedWorkerId.equals(reportedWorkerId)) {
        reject(
            Status.PERMISSION_DENIED.withDescription(
                "Registered worker identity does not match authenticated identity"));
        return;
      }

      if (advertisesOneCodecFamilyTwice(request.getRegistration())) {
        reject(
            Status.INVALID_ARGUMENT.withDescription(
                "Worker registration lists more than one encoder for a codec family"));
        return;
      }

      workerSessionId =
          workerConnections.register(
              authenticatedWorkerId, request.getRegistration(), responseObserver);
      registered = true;
    }

    // The contract allows one encoder for each codec family: the one that runs every job of it,
    // and one software encoder beside it.
    private static boolean advertisesOneCodecFamilyTwice(WorkerRegistration registration) {
      var capabilities = registration.getCapabilities();
      return LiveWorkerConnectionRegistry.listsOneCodecFamilyTwice(
              capabilities.getVideoEncodersList())
          || LiveWorkerConnectionRegistry.listsOneCodecFamilyTwice(
              capabilities.getSoftwareVideoEncodersList());
    }

    @Override
    public void onError(Throwable throwable) {
      log.warn(
          "Worker {} session {} control stream failed",
          authenticatedWorkerId,
          workerSessionId,
          throwable);
      disconnect();
    }

    @Override
    public void onCompleted() {
      disconnect();
      responseObserver.onCompleted();
    }

    private void disconnect() {
      if (registered) {
        workerConnections.disconnect(authenticatedWorkerId, workerSessionId);
      }
    }

    private void handleSessionEvent(EstablishWorkerSessionRequest request) {
      switch (request.getEventCase()) {
        case JOB_ATTEMPT_STARTED -> startJobAttempt(request.getJobAttemptStarted());
        case JOB_ATTEMPT_FAILED -> reportFailedJobAttempt(request.getJobAttemptFailed());
        case JOB_ATTEMPT_COMPLETED -> completeOrWarn(request.getJobAttemptCompleted());
        case JOB_ATTEMPT_STOPPED ->
            finishOrWarn(request.getJobAttemptStopped().getJobAttemptId(), "stopped");
        case PROBE_CAPACITY_CHANGED -> changeProbeCapacity(request.getProbeCapacityChanged());
        case VIDEO_ENCODERS_CHANGED -> changeVideoEncoders(request.getVideoEncodersChanged());
        case JOB_ATTEMPT_PROGRESS ->
            workerConnections.reportProgress(
                authenticatedWorkerId, workerSessionId, request.getJobAttemptProgress());
        default ->
            log.warn(
                "Ignoring unexpected {} event on established session of worker {}",
                request.getEventCase(),
                authenticatedWorkerId);
      }
    }

    private void changeVideoEncoders(VideoEncodersChanged changed) {
      if (workerConnections.changeVideoEncoders(authenticatedWorkerId, workerSessionId, changed)) {
        log.info(
            "Worker {} now offers encoders {}",
            authenticatedWorkerId,
            changed.getVideoEncodersList().stream()
                .map(encoder -> encoder.getBackend().getEncoder())
                .toList());
        return;
      }

      log.warn(
          "Ignoring the encoders that worker {} reported: the change lists a codec family twice or"
              + " names a session that is no longer current",
          authenticatedWorkerId);
    }

    private void changeProbeCapacity(ProbeCapacityChanged changed) {
      if (workerConnections.tryChangeProbeCapacity(
          authenticatedWorkerId, workerSessionId, changed)) {
        return;
      }

      log.warn(
          "Ignoring the probe capacity that worker {} reported on a session that is no longer"
              + " current",
          authenticatedWorkerId);
    }

    private void startJobAttempt(JobAttemptStarted started) {
      log.debug(
          "Worker {} started job attempt {}",
          authenticatedWorkerId,
          fromProto(started.getJobAttemptId()));
      workerConnections.checkReportedEncoderBackend(
          authenticatedWorkerId, workerSessionId, started);
    }

    private void reportFailedJobAttempt(JobAttemptFailed failed) {
      switch (workerConnections.failJobAttempt(authenticatedWorkerId, workerSessionId, failed)) {
        case ReportedJobAttemptEnd.Released(var job) ->
            log.warn(
                "Worker {} failed variant {} of stream session {}: {}",
                authenticatedWorkerId,
                job.getVariant().getVariantLabel(),
                fromProto(job.getStreamSessionId()),
                failed.getFailure());
        case ReportedJobAttemptEnd.StoppedByServer() ->
            log.debug(
                "Worker {} reported failure {} for job attempt {}, which the server stopped",
                authenticatedWorkerId,
                failed.getFailure(),
                fromProto(failed.getJobAttemptId()));
        case ReportedJobAttemptEnd.Unknown() ->
            log.warn(
                "Worker {} failed unknown job attempt {}: {}",
                authenticatedWorkerId,
                fromProto(failed.getJobAttemptId()),
                failed.getFailure());
      }
    }

    private void finishOrWarn(Uuid jobAttemptId, String detail) {
      var attemptId = fromProto(jobAttemptId);
      logReportedEnd(
          workerConnections.endReportedJobAttempt(
              authenticatedWorkerId, workerSessionId, attemptId),
          attemptId,
          detail);
    }

    private void completeOrWarn(JobAttemptCompleted completed) {
      logReportedEnd(
          workerConnections.completeReportedJobAttempt(
              authenticatedWorkerId, workerSessionId, completed),
          fromProto(completed.getJobAttemptId()),
          "completed");
    }

    private void logReportedEnd(ReportedJobAttemptEnd end, UUID attemptId, String detail) {
      switch (end) {
        case ReportedJobAttemptEnd.Released(var job) ->
            log.debug(
                "Worker {} ended job attempt {} for stream session {} variant {} ({})",
                authenticatedWorkerId,
                attemptId,
                fromProto(job.getStreamSessionId()),
                job.getVariant().getVariantLabel(),
                detail);
        case ReportedJobAttemptEnd.StoppedByServer() ->
            log.debug(
                "Worker {} reported {} for job attempt {}, which the server stopped",
                authenticatedWorkerId,
                detail,
                attemptId);
        case ReportedJobAttemptEnd.Unknown() ->
            log.warn(
                "Worker {} reported {} for unknown job attempt {}",
                authenticatedWorkerId,
                detail,
                attemptId);
      }
    }

    private void reject(Status status) {
      log.warn(
          "Rejecting worker session of worker {}: {}",
          authenticatedWorkerId,
          status.getDescription());
      responseObserver.onError(status.asRuntimeException());
    }
  }
}
