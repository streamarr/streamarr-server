package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.dispatched;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.LoopbackProbeWorker;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.ReportProbeAttemptResponse;
import io.grpc.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("IntegrationTest")
@DisplayName("Probe Report Call Integration Tests")
@Timeout(60)
class ProbeReportCallIT {

  private static final int PROBE_VERSION = 1;
  // worker_session.proto states the largest message that the server accepts on every call.
  private static final int MESSAGE_LIMIT_BYTES = 131_072;
  // Each keyframe takes the widest sint64 encoding twice, 20 bytes, so this many keyframes make a
  // probe report of more than three messages of the largest size.
  private static final int KEYFRAMES_OF_LARGE_REPORT = 20_000;
  private static final long WIDEST_KEYFRAME_TIME_DELTA = Long.MIN_VALUE;

  @Test
  @DisplayName(
      "Should complete the probe with the whole report when a worker sends a report larger than"
          + " one message in several messages")
  void
      shouldCompleteProbeWithWholeReportWhenWorkerSendsReportLargerThanOneMessageInSeveralMessages()
          throws Exception {
    try (var server = server()) {
      server.start();
      try (var worker = worker(server.port())) {
        var request = probeRequest();
        var pending = dispatched(server.dispatchProbe(request));
        assertThat(worker.nextResponse().getStartProbe().getRequest()).isEqualTo(request);
        var report = largeReport(request);

        var answer = worker.report(report);

        assertThat(report.getSerializedSize()).isGreaterThan(MESSAGE_LIMIT_BYTES);
        assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo(report);
        assertThat(answer.get(10, TimeUnit.SECONDS))
            .isEqualTo(
                ReportProbeAttemptResponse.newBuilder()
                    .setProbeAttemptId(request.getProbeAttemptId())
                    .setAcceptedLengthBytes(report.getSerializedSize())
                    .build());
      }
    }
  }

  @Test
  @DisplayName(
      "Should end only the report call and fail the probe for a retry when one message of the"
          + " report call exceeds the limit of one message")
  void shouldEndOnlyReportCallAndFailProbeForRetryWhenOneMessageOfReportCallExceedsLimit()
      throws Exception {
    try (var server = server()) {
      server.start();
      try (var worker = worker(server.port())) {
        var request = probeRequest();
        var pending = dispatched(server.dispatchProbe(request));
        assertThat(worker.nextResponse().getStartProbe().getRequest()).isEqualTo(request);
        var report = largeReport(request);

        var answer = worker.report(report, report.getSerializedSize());

        assertThatThrownBy(() -> answer.get(10, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .cause()
            .extracting(failure -> Status.fromThrowable(failure).getCode())
            .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThatThrownBy(() -> pending.get(10, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .cause()
            .isExactlyInstanceOf(ProbeExecutionException.class)
            .asInstanceOf(InstanceOfAssertFactories.type(ProbeExecutionException.class))
            .extracting(ProbeExecutionException::reason)
            .isEqualTo(ItemFailureReason.TEMPORARY);
        assertThat(worker.terminated()).isNotDone();
        var next = probeRequest();
        var nextPending = dispatched(server.dispatchProbe(next));
        assertThat(worker.nextResponse().getStartProbe().getRequest()).isEqualTo(next);
        var nextReport = invalidMedia(next).build();
        worker.report(nextReport);
        assertThat(nextPending.get(10, TimeUnit.SECONDS))
            .as("The worker session still starts and completes probes")
            .isEqualTo(nextReport);
      }
    }
  }

  private static WorkerSessionServer server() {
    return WorkerSessionServer.builder()
        .eventPublisher(new CapturingEventPublisher())
        .configuration(serverConfigurationBuilder().build())
        .segmentStore(new FakeSegmentStore())
        .meterRegistry(new SimpleMeterRegistry())
        .build();
  }

  private static LoopbackProbeWorker worker(int port) throws Exception {
    return LoopbackProbeWorker.builder()
        .port(port)
        .probeVersion(PROBE_VERSION)
        .backgroundProbeCapacity(2)
        .build();
  }

  private static ProbeRequest probeRequest() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(PROBE_VERSION)
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
                .setRelativeKey("movie.mkv"))
        .build();
  }

  private static ProbeAttemptReport largeReport(ProbeRequest request) {
    var deltas = Collections.nCopies(KEYFRAMES_OF_LARGE_REPORT, WIDEST_KEYFRAME_TIME_DELTA);
    return invalidMedia(request)
        .addAllKeyframePresentationTimeDeltas(deltas)
        .addAllKeyframeDecodeTimeDeltas(deltas)
        .build();
  }

  private static ProbeAttemptReport.Builder invalidMedia(ProbeRequest request) {
    return ProbeAttemptReport.newBuilder()
        .setResult(
            ProbeAttemptResult.newBuilder()
                .setProbeAttemptId(request.getProbeAttemptId())
                .setProbeVersion(request.getProbeVersion())
                .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA));
  }
}
