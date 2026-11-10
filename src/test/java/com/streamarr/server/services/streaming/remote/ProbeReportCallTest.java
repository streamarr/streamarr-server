package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.ReportingWorker.invalidMedia;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.google.protobuf.ByteString;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptReportMetadata;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.ReportProbeAttemptRequest;
import com.streamarr.transcode.v1.ReportProbeAttemptResponse;
import com.streamarr.transcode.v1.StreamMeasurementIntervals;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Probe Report Call Tests")
@Timeout(30)
class ProbeReportCallTest {

  private static final ProbeReportLimits LIMITS =
      ProbeReportLimits.builder().keyframeLimit(4).measuredKeyframeIntervalLimit(6).build();
  // The server's limit of report calls that one worker holds open.
  private static final int REPORT_CALLS_PER_WORKER = 24;
  // Room for one large report, and for no second report of more than 60,000 bytes beside it.
  private static final ProbeReportLimits TIGHT_MEMORY =
      ProbeReportLimits.builder().lengthLimitBytes(150_000).memoryBudgetBytes(200_000).build();
  private static final int KEYFRAMES_OF_LARGE_REPORT = 7_000;

  @Test
  @DisplayName(
      "Should complete the probe with the report and answer with its length when a worker reports"
          + " the attempt in several messages")
  void
      shouldCompleteProbeWithReportAndAnswerWithItsLengthWhenWorkerReportsAttemptInSeveralMessages()
          throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var report = reportAtBothLimits(probe.request());

    var call = worker.report(probe.request(), report);

    assertThat(probe.outcome().get(1, TimeUnit.SECONDS)).isEqualTo(report);
    assertThat(call.answer().get(1, TimeUnit.SECONDS))
        .isEqualTo(
            ReportProbeAttemptResponse.newBuilder()
                .setProbeAttemptId(probe.request().getProbeAttemptId())
                .setAcceptedLengthBytes(report.getSerializedSize())
                .build());
  }

  @Test
  @DisplayName("Should ask for one message at a time when a worker reports a probe attempt")
  void shouldAskForOneMessageAtATimeWhenWorkerReportsProbeAttempt() throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var bytes = reportAtBothLimits(probe.request()).toByteString();
    var call = worker.openReportCall();
    var requestedAtOpen = call.requestedMessages();

    call.sendMetadata(worker.metadata(probe.request()).setReportLengthBytes(bytes.size()));
    var requestedAfterMetadata = call.requestedMessages();
    call.sendData(bytes.substring(0, 1));

    assertThat(call.readsOneMessageAtATime()).isTrue();
    assertThat(requestedAtOpen).isEqualTo(1);
    assertThat(requestedAfterMetadata).isEqualTo(2);
    assertThat(call.requestedMessages()).isEqualTo(3);
  }

  @Test
  @DisplayName("Should refuse a report call when it carries no authenticated worker identity")
  void shouldRefuseReportCallWhenItCarriesNoAuthenticatedWorkerIdentity() throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);

    var call = worker.openReportCallWithoutWorkerIdentity();

    assertThat(call.refusal()).isEqualTo(Status.Code.UNAUTHENTICATED);
  }

  @Test
  @DisplayName("Should refuse a report call when its first message is not the metadata")
  void shouldRefuseReportCallWhenItsFirstMessageIsNotTheMetadata() throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var call = worker.openReportCall();

    call.sendData(invalidMedia(probe.request()).build().toByteString());

    assertThat(call.refusal()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(probe.outcome()).isNotDone();
  }

  @Test
  @DisplayName("Should refuse a report call when it ends before its metadata")
  void shouldRefuseReportCallWhenItEndsBeforeItsMetadata() throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var call = worker.openReportCall();

    call.halfClose();

    assertThat(call.refusal()).isEqualTo(Status.Code.INVALID_ARGUMENT);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Unowned.class)
  @DisplayName(
      "Should refuse a report call and leave the probe pending when its metadata does not name a"
          + " probe attempt of the worker's live session")
  void shouldRefuseReportCallAndLeaveProbePendingWhenItsMetadataDoesNotNameAnAttemptOfLiveSession(
      Unowned unowned) throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var call = worker.openReportCall();

    call.sendMetadata(unowned.change(worker.metadata(probe.request())).setReportLengthBytes(1));

    assertThat(call.refusal()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(probe.outcome()).isNotDone();
  }

  private enum Unowned {
    ANOTHER_WORKER {
      @Override
      ProbeAttemptReportMetadata.Builder change(ProbeAttemptReportMetadata.Builder metadata) {
        metadata.getWorkerBuilder().setWorkerId(toProto(UUID.randomUUID()));
        return metadata;
      }
    },
    ANOTHER_BOOT_OF_THE_WORKER {
      @Override
      ProbeAttemptReportMetadata.Builder change(ProbeAttemptReportMetadata.Builder metadata) {
        metadata.getWorkerBuilder().setBootId(toProto(UUID.randomUUID()));
        return metadata;
      }
    },
    A_WORKER_SESSION_THAT_THE_SERVER_NEVER_ACCEPTED {
      @Override
      ProbeAttemptReportMetadata.Builder change(ProbeAttemptReportMetadata.Builder metadata) {
        return metadata.setWorkerSessionId(toProto(UUID.randomUUID()));
      }
    },
    A_PROBE_ATTEMPT_THAT_THE_SERVER_NEVER_STARTED {
      @Override
      ProbeAttemptReportMetadata.Builder change(ProbeAttemptReportMetadata.Builder metadata) {
        return metadata.setProbeAttemptId(toProto(UUID.randomUUID()));
      }
    };

    abstract ProbeAttemptReportMetadata.Builder change(ProbeAttemptReportMetadata.Builder metadata);
  }

  @Test
  @DisplayName(
      "Should refuse a report call when its metadata names the worker's earlier session and not"
          + " its live one")
  void shouldRefuseReportCallWhenItsMetadataNamesWorkersEarlierSessionAndNotItsLiveOne()
      throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var earlierSession = worker.reconnect();
    var probe = worker.dispatch();
    var call = worker.openReportCall();

    call.sendMetadata(
        worker
            .metadata(probe.request())
            .setWorkerSessionId(earlierSession)
            .setReportLengthBytes(1));

    assertThat(call.refusal()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(probe.outcome()).isNotDone();
  }

  @Test
  @DisplayName(
      "Should refuse a report call when its metadata names a probe attempt of another worker's"
          + " session")
  void shouldRefuseReportCallWhenItsMetadataNamesProbeAttemptOfAnotherWorkersSession()
      throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var other = worker.anotherWorker();
    var probe = worker.dispatch();
    var asItself = other.openReportCall();
    var asTheAttemptsWorker = other.openReportCall();

    asItself.sendMetadata(other.metadata(probe.request()).setReportLengthBytes(1));
    asTheAttemptsWorker.sendMetadata(worker.metadata(probe.request()).setReportLengthBytes(1));

    assertThat(asItself.refusal()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(asTheAttemptsWorker.refusal()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(probe.outcome()).isNotDone();
  }

  @Test
  @DisplayName("Should refuse a report call when its probe attempt has already ended")
  void shouldRefuseReportCallWhenItsProbeAttemptHasAlreadyEnded() throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var report = invalidMedia(probe.request()).build();
    worker.report(probe.request(), report);

    var late = worker.report(probe.request(), report);

    assertThat(late.refusal()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(probe.outcome().get(1, TimeUnit.SECONDS)).isEqualTo(report);
  }

  @Test
  @DisplayName(
      "Should refuse a second report call and keep reading the first when both report one probe"
          + " attempt")
  void shouldRefuseSecondReportCallAndKeepReadingFirstWhenBothReportOneProbeAttempt()
      throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var report = invalidMedia(probe.request()).build();
    var bytes = report.toByteString();
    var first = worker.openReportCall();
    first.sendMetadata(worker.metadata(probe.request()).setReportLengthBytes(bytes.size()));
    var second = worker.openReportCall();

    second.sendMetadata(worker.metadata(probe.request()).setReportLengthBytes(bytes.size()));
    first.sendData(bytes);
    first.halfClose();

    assertThat(second.refusal()).isEqualTo(Status.Code.ALREADY_EXISTS);
    assertThat(first.answer().get(1, TimeUnit.SECONDS).getAcceptedLengthBytes())
        .isEqualTo(bytes.size());
    assertThat(probe.outcome().get(1, TimeUnit.SECONDS)).isEqualTo(report);
  }

  @Test
  @DisplayName(
      "Should refuse one more report call as exhausted when a worker holds as many open as the"
          + " probe report limit states")
  void shouldRefuseOneMoreReportCallAsExhaustedWhenWorkerHoldsAsManyOpenAsProbeReportLimitStates()
      throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var stated = worker.commands().getFirst().getSessionAccepted().getProbeReportLimit();
    var open = new ArrayList<ReportCall>();
    for (var opened = 0; opened < REPORT_CALLS_PER_WORKER; opened++) {
      open.add(worker.openReportCall());
    }

    var excess = worker.openReportCall();
    var ofAnotherWorker = worker.anotherWorker().openReportCall();

    assertThat(stated).isEqualTo(REPORT_CALLS_PER_WORKER);
    assertThat(open).allMatch(ReportCall::isOpen);
    assertThat(excess.refusal()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    assertThat(ofAnotherWorker.isOpen()).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(CallEnd.class)
  @DisplayName(
      "Should accept another report call when one of a worker's report calls at the limit ends")
  void shouldAcceptAnotherReportCallWhenOneOfWorkersReportCallsAtLimitEnds(CallEnd end)
      throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var ending = worker.openReportCall();
    for (var opened = 1; opened < REPORT_CALLS_PER_WORKER; opened++) {
      worker.openReportCall();
    }

    end.end(worker, ending, probe.request());
    var next = worker.openReportCall();

    assertThat(next.isOpen()).isTrue();
  }

  private enum CallEnd {
    ANSWERED {
      @Override
      void end(ReportingWorker worker, ReportCall call, ProbeRequest request) throws Exception {
        call.report(worker.metadata(request), invalidMedia(request).build());
      }
    },
    REFUSED {
      @Override
      void end(ReportingWorker worker, ReportCall call, ProbeRequest request) {
        call.halfClose();
      }
    },
    CANCELLED {
      @Override
      void end(ReportingWorker worker, ReportCall call, ProbeRequest request) {
        call.cancel();
      }
    };

    abstract void end(ReportingWorker worker, ReportCall call, ProbeRequest request)
        throws Exception;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("contractViolations")
  @DisplayName(
      "Should fail the probe as a contract violation and end only its report call when the report"
          + " call breaks the contract")
  void shouldFailProbeAsContractViolationAndEndOnlyItsReportCallWhenReportCallBreaksContract(
      String rule, BrokenReport broken) throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var other = worker.dispatch();
    var call = worker.openReportCall();

    broken.send(call, worker.metadata(probe.request()), probe.request());

    assertThatThrownBy(() -> probe.outcome().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseExactlyInstanceOf(ProbeContractViolationException.class);
    assertThat(call.refusal()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(worker.sessionEnd()).isNotDone();
    assertThat(worker.isConnected()).isTrue();
    var otherReport = invalidMedia(other.request()).build();
    worker.report(other.request(), otherReport);
    assertThat(other.outcome().get(1, TimeUnit.SECONDS)).isEqualTo(otherReport);
  }

  @FunctionalInterface
  private interface BrokenReport {
    void send(ReportCall call, ProbeAttemptReportMetadata.Builder metadata, ProbeRequest request)
        throws Exception;
  }

  static Stream<Arguments> contractViolations() {
    return Stream.concat(brokenFraming(), brokenReports());
  }

  private static Stream<Arguments> brokenFraming() {
    return Stream.of(
        violation(
            "a stated length of zero",
            (call, metadata, request) -> call.sendMetadata(metadata.setReportLengthBytes(0))),
        violation(
            "a stated length above the length limit",
            (call, metadata, request) ->
                call.sendMetadata(metadata.setReportLengthBytes(LIMITS.lengthLimitBytes() + 1))),
        violation(
            "a stated length beyond a signed 64-bit count",
            (call, metadata, request) -> call.sendMetadata(metadata.setReportLengthBytes(-1))),
        violation(
            "a data message without bytes",
            (call, metadata, request) -> {
              call.sendMetadata(metadata.setReportLengthBytes(8));
              call.sendData(ByteString.EMPTY);
            }),
        violation(
            "a second metadata message",
            (call, metadata, request) -> {
              call.sendMetadata(metadata.setReportLengthBytes(8));
              call.sendMetadata(metadata);
            }),
        violation(
            "a message without a frame",
            (call, metadata, request) -> {
              call.sendMetadata(metadata.setReportLengthBytes(8));
              call.send(ReportProbeAttemptRequest.getDefaultInstance());
            }),
        violation(
            "more bytes than stated in one message",
            (call, metadata, request) -> {
              var bytes = invalidMedia(request).build().toByteString();
              call.sendMetadata(metadata.setReportLengthBytes(bytes.size() - 1L));
              call.sendData(bytes);
            }),
        violation(
            "a message after every stated byte",
            (call, metadata, request) -> {
              var bytes = invalidMedia(request).build().toByteString();
              call.sendMetadata(metadata.setReportLengthBytes(bytes.size()));
              call.sendData(bytes);
              call.sendData(bytes.substring(0, 1));
            }),
        violation(
            "fewer bytes than stated at the half-close",
            (call, metadata, request) -> {
              var bytes = invalidMedia(request).build().toByteString();
              call.sendMetadata(metadata.setReportLengthBytes(bytes.size() + 1L));
              call.sendData(bytes);
              call.halfClose();
            }),
        violation(
            "no bytes at the half-close",
            (call, metadata, request) -> {
              call.sendMetadata(metadata.setReportLengthBytes(8));
              call.halfClose();
            }),
        violation(
            "bytes that do not decode as a probe report",
            (call, metadata, request) -> {
              var bytes = ByteString.copyFrom(new byte[] {-1, -1, -1, -1, -1, -1, -1, -1});
              call.sendMetadata(metadata.setReportLengthBytes(bytes.size()));
              call.sendData(bytes);
              call.halfClose();
            }));
  }

  private static Stream<Arguments> brokenReports() {
    return Stream.of(
        brokenReport("no result", request -> withKeyframes(invalidMedia(request).clearResult(), 1)),
        brokenReport(
            "a result that names another probe attempt",
            request -> {
              var report = invalidMedia(request);
              report.getResultBuilder().setProbeAttemptId(toProto(UUID.randomUUID()));
              return report;
            }),
        brokenReport(
            "more keyframes than the keyframe limit",
            request -> withKeyframes(invalidMedia(request), LIMITS.keyframeLimit() + 1)),
        brokenReport(
            "more presentation time deltas than decode time deltas",
            request ->
                withKeyframes(invalidMedia(request), 2).addKeyframePresentationTimeDeltas(1)),
        brokenReport(
            "more decode time deltas than presentation time deltas",
            request -> withKeyframes(invalidMedia(request), 2).addKeyframeDecodeTimeDeltas(1)),
        brokenReport(
            "more measured keyframe intervals over its streams than the limit",
            request ->
                invalidMedia(request)
                    .addStreamMeasurementIntervals(intervals(0, 4))
                    .addStreamMeasurementIntervals(intervals(1, 3))),
        brokenReport(
            "a stream with more interval bytes than interval packet counts",
            request ->
                invalidMedia(request)
                    .addStreamMeasurementIntervals(
                        intervals(0, 2).toBuilder().addKeyframeIntervalBytes(1))),
        brokenReport(
            "a stream with more interval packet counts than interval bytes",
            request ->
                invalidMedia(request)
                    .addStreamMeasurementIntervals(
                        intervals(0, 2).toBuilder().addKeyframeIntervalPacketCounts(1))),
        brokenReport(
            "a stream without intervals",
            request -> invalidMedia(request).addStreamMeasurementIntervals(intervals(0, 0))),
        brokenReport(
            "a stream listed twice",
            request ->
                invalidMedia(request)
                    .addStreamMeasurementIntervals(intervals(0, 1))
                    .addStreamMeasurementIntervals(intervals(0, 1))));
  }

  private static Arguments violation(String rule, BrokenReport broken) {
    return arguments(rule, broken);
  }

  private static Arguments brokenReport(
      String rule, Function<ProbeRequest, ProbeAttemptReport.Builder> report) {
    return violation(
        rule, (call, metadata, request) -> call.report(metadata, report.apply(request).build()));
  }

  @Test
  @DisplayName(
      "Should accept a probe report when it holds exactly as many keyframes and measured keyframe"
          + " intervals as the limits state")
  void shouldAcceptProbeReportWhenItHoldsExactlyAsManyKeyframesAndIntervalsAsLimitsState()
      throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var report = reportAtBothLimits(probe.request());

    worker.report(probe.request(), report);

    assertThat(probe.outcome().get(1, TimeUnit.SECONDS)).isEqualTo(report);
  }

  @Test
  @DisplayName(
      "Should read a probe report when its metadata states exactly the length that the length"
          + " limit states")
  void shouldReadProbeReportWhenItsMetadataStatesExactlyLengthThatLengthLimitStates()
      throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var call = worker.openReportCall();

    call.sendMetadata(
        worker.metadata(probe.request()).setReportLengthBytes(LIMITS.lengthLimitBytes()));

    assertThat(call.isOpen()).isTrue();
    assertThat(call.unreadDemand()).isEqualTo(1);
    assertThat(probe.outcome()).isNotDone();
  }

  @Test
  @DisplayName(
      "Should fail the probe as misconfigured, not as a contract violation, when the report names"
          + " another probe version")
  void shouldFailProbeAsMisconfiguredNotAsContractViolationWhenReportNamesAnotherProbeVersion()
      throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    // A worker of another probe version may also build its report by other rules.
    var report = withKeyframes(invalidMedia(probe.request()), LIMITS.keyframeLimit() + 1);
    report.getResultBuilder().setProbeVersion(ReportingWorker.PROBE_VERSION + 1);

    var call = worker.report(probe.request(), report.build());

    assertThatThrownBy(() -> probe.outcome().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isExactlyInstanceOf(ProbeExecutionException.class)
        .asInstanceOf(InstanceOfAssertFactories.type(ProbeExecutionException.class))
        .extracting(ProbeExecutionException::reason)
        .isEqualTo(ItemFailureReason.MISCONFIGURED);
    assertThat(call.refusal()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(worker.isConnected()).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Progress.class)
  @DisplayName(
      "Should fail the probe for a retry, as when its worker is lost, when its report call ends"
          + " without the answer and without a contract violation")
  void shouldFailProbeForRetryWhenItsReportCallEndsWithoutAnswerAndWithoutContractViolation(
      Progress progress) throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var call = worker.openReportCall();
    progress.send(call, worker.metadata(probe.request()), probe.request());

    call.cancel();

    assertThatThrownBy(() -> probe.outcome().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isExactlyInstanceOf(ProbeExecutionException.class)
        .asInstanceOf(InstanceOfAssertFactories.type(ProbeExecutionException.class))
        .extracting(ProbeExecutionException::reason)
        .isEqualTo(ItemFailureReason.TEMPORARY);
    assertThat(worker.isConnected()).isTrue();
    assertThat(worker.tryDispatch()).isInstanceOf(ProbeDispatch.Dispatched.class);
  }

  private enum Progress {
    AFTER_THE_METADATA {
      @Override
      void send(ReportCall call, ProbeAttemptReportMetadata.Builder metadata, ProbeRequest request)
          throws Exception {
        call.sendMetadata(metadata.setReportLengthBytes(8));
      }
    },
    AFTER_SOME_BYTES {
      @Override
      void send(ReportCall call, ProbeAttemptReportMetadata.Builder metadata, ProbeRequest request)
          throws Exception {
        call.sendMetadata(metadata.setReportLengthBytes(8));
        call.sendData(ByteString.copyFrom(new byte[] {1, 2, 3}));
      }
    },
    AFTER_EVERY_BYTE {
      @Override
      void send(ReportCall call, ProbeAttemptReportMetadata.Builder metadata, ProbeRequest request)
          throws Exception {
        var bytes = invalidMedia(request).build().toByteString();
        call.sendMetadata(metadata.setReportLengthBytes(bytes.size()));
        call.sendData(bytes);
      }
    };

    abstract void send(
        ReportCall call, ProbeAttemptReportMetadata.Builder metadata, ProbeRequest request)
        throws Exception;
  }

  @Test
  @DisplayName("Should leave every probe pending when a report call ends before its metadata")
  void shouldLeaveEveryProbePendingWhenReportCallEndsBeforeItsMetadata() throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var call = worker.openReportCall();

    call.cancel();

    assertThat(probe.outcome()).isNotDone();
  }

  @Test
  @DisplayName(
      "Should hold the probe's unit of capacity until its report call ends when the worker reports"
          + " the attempt")
  void shouldHoldProbesUnitOfCapacityUntilItsReportCallEndsWhenWorkerReportsAttempt()
      throws Exception {
    var worker =
        ReportingWorker.connectedTo(
            WorkerSessionServerConfiguration.builder().probeReportLimits(LIMITS),
            ReportingWorker.capacities().setBackgroundProbeCapacity(1));
    var probe = worker.dispatch();
    var bytes = invalidMedia(probe.request()).build().toByteString();
    var call = worker.openReportCall();
    call.sendMetadata(worker.metadata(probe.request()).setReportLengthBytes(bytes.size()));
    call.sendData(bytes);

    var whileReporting = worker.tryDispatch();
    call.halfClose();
    var afterTheAnswer = worker.tryDispatch();

    assertThat(whileReporting).isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
    assertThat(afterTheAnswer).isInstanceOf(ProbeDispatch.Dispatched.class);
  }

  @Test
  @DisplayName(
      "Should fail the probe for a retry and end its open report call when the worker session"
          + " ends")
  void shouldFailProbeForRetryAndEndItsOpenReportCallWhenWorkerSessionEnds() throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var call = worker.openReportCall();
    call.sendMetadata(worker.metadata(probe.request()).setReportLengthBytes(8));

    worker.disconnect();

    assertThatThrownBy(() -> probe.outcome().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isExactlyInstanceOf(ProbeExecutionException.class)
        .asInstanceOf(InstanceOfAssertFactories.type(ProbeExecutionException.class))
        .extracting(ProbeExecutionException::reason)
        .isEqualTo(ItemFailureReason.TEMPORARY);
    assertThat(call.refusal()).isEqualTo(Status.Code.PERMISSION_DENIED);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(LateArrival.class)
  @DisplayName(
      "Should end the report call once and read nothing further when what the worker sent arrives"
          + " after the worker session ended")
  void shouldEndReportCallOnceAndReadNothingFurtherWhenWhatWorkerSentArrivesAfterSessionEnded(
      LateArrival late) throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var call = worker.openReportCall();
    call.sendMetadata(worker.metadata(probe.request()).setReportLengthBytes(8));
    worker.disconnect();
    var requestedBeforeTheArrival = call.requestedMessages();

    late.arriveOn(call);

    // The call's fake fails the test when the server ends one call twice.
    assertThat(call.refusal()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(call.requestedMessages()).isEqualTo(requestedBeforeTheArrival);
  }

  private enum LateArrival {
    A_MESSAGE {
      @Override
      void arriveOn(ReportCall call) throws Exception {
        call.sendData(ByteString.copyFrom(new byte[] {1, 2, 3}));
      }
    },
    THE_HALF_CLOSE {
      @Override
      void arriveOn(ReportCall call) {
        call.halfClose();
      }
    },
    A_CANCELLATION {
      @Override
      void arriveOn(ReportCall call) {
        call.cancel();
      }
    };

    abstract void arriveOn(ReportCall call) throws Exception;
  }

  @Test
  @DisplayName(
      "Should read the report call of a cancelled probe and change nothing by its content when the"
          + " worker reports the attempt")
  void shouldReadReportCallOfCancelledProbeAndChangeNothingByItsContentWhenWorkerReportsAttempt()
      throws Exception {
    var worker =
        ReportingWorker.connectedTo(
            WorkerSessionServerConfiguration.builder().probeReportLimits(LIMITS),
            ReportingWorker.capacities().setBackgroundProbeCapacity(1));
    var probe = worker.dispatch();
    probe.outcome().cancel(true);
    var whileTheWorkerStopsTheProbe = worker.tryDispatch();
    var report = invalidMedia(probe.request()).build();

    var call = worker.report(probe.request(), report);

    assertThat(whileTheWorkerStopsTheProbe)
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
    assertThat(call.answer().get(1, TimeUnit.SECONDS).getAcceptedLengthBytes())
        .isEqualTo(report.getSerializedSize());
    assertThatThrownBy(() -> probe.outcome().get(1, TimeUnit.SECONDS))
        .isInstanceOf(CancellationException.class);
    assertThat(worker.tryDispatch()).isInstanceOf(ProbeDispatch.Dispatched.class);
    assertThat(worker.isConnected()).isTrue();
  }

  @Test
  @DisplayName(
      "Should read no message of a report call, and keep the call open, while its probe report"
          + " does not fit the memory budget")
  void shouldReadNoMessageOfReportCallAndKeepCallOpenWhileItsProbeReportDoesNotFitMemoryBudget()
      throws Exception {
    var worker = ReportingWorker.connectedTo(TIGHT_MEMORY);
    var reading = holdRoomForLargeReport(worker);
    var probe = worker.dispatch();
    var waiting = worker.openReportCall();

    waiting.sendMetadata(worker.metadata(probe.request()).setReportLengthBytes(100_000));

    assertThat(reading.unreadDemand()).isEqualTo(1);
    assertThat(waiting.unreadDemand()).isZero();
    assertThat(waiting.isOpen()).isTrue();
    assertThat(probe.outcome()).isNotDone();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(RoomHolderEnd.class)
  @DisplayName(
      "Should read on a report call that waits for the memory budget when the report call that"
          + " holds the room ends")
  void shouldReadOnReportCallThatWaitsForMemoryBudgetWhenReportCallThatHoldsRoomEnds(
      RoomHolderEnd end) throws Exception {
    var worker = ReportingWorker.connectedTo(TIGHT_MEMORY);
    var held = worker.dispatch();
    var holder = worker.openReportCall();
    var largeReport = largeReport(held.request());
    holder.sendMetadata(
        worker.metadata(held.request()).setReportLengthBytes(largeReport.getSerializedSize()));
    var waiting = waitForRoom(worker, 100_000);
    var demandWhileWaiting = waiting.unreadDemand();

    end.end(holder, largeReport);

    assertThat(demandWhileWaiting).isZero();
    assertThat(waiting.unreadDemand()).isEqualTo(1);
    assertThat(waiting.isOpen()).isTrue();
  }

  private enum RoomHolderEnd {
    ANSWERED {
      @Override
      void end(ReportCall holder, ProbeAttemptReport report) throws Exception {
        var bytes = report.toByteString();
        holder.sendData(bytes.substring(0, bytes.size() / 2));
        holder.sendData(bytes.substring(bytes.size() / 2));
        holder.halfClose();
        assertThat(holder.answer()).isCompleted();
      }
    },
    BROKE_THE_CONTRACT {
      @Override
      void end(ReportCall holder, ProbeAttemptReport report) throws Exception {
        holder.sendData(ByteString.EMPTY);
        assertThat(holder.refusal()).isEqualTo(Status.Code.INVALID_ARGUMENT);
      }
    },
    CANCELLED {
      @Override
      void end(ReportCall holder, ProbeAttemptReport report) {
        holder.cancel();
      }
    };

    abstract void end(ReportCall holder, ProbeAttemptReport report) throws Exception;
  }

  @Test
  @DisplayName(
      "Should give room to the waiting report calls in the order in which they asked when a"
          + " later probe report would fit before an earlier one")
  void shouldGiveRoomToWaitingReportCallsInOrderInWhichTheyAskedWhenLaterReportWouldFitFirst()
      throws Exception {
    var worker = ReportingWorker.connectedTo(TIGHT_MEMORY);
    var holder = holdRoomForLargeReport(worker);
    var earlier = waitForRoom(worker, 100_000);
    var later = waitForRoom(worker, 50_000);
    var last = waitForRoom(worker, 150_000);
    var demandOfLaterBehindEarlier = later.unreadDemand();

    holder.cancel();
    var demandOfLastBehindBoth = last.unreadDemand();
    earlier.cancel();

    assertThat(demandOfLaterBehindEarlier).isZero();
    assertThat(earlier.requestedMessages()).isEqualTo(2);
    assertThat(later.unreadDemand()).isEqualTo(1);
    assertThat(demandOfLastBehindBoth).isZero();
    assertThat(last.unreadDemand()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should give room to the next waiting report call when a report call ends while it waits"
          + " for the memory budget")
  void shouldGiveRoomToNextWaitingReportCallWhenReportCallEndsWhileItWaitsForMemoryBudget()
      throws Exception {
    var worker = ReportingWorker.connectedTo(TIGHT_MEMORY);
    holdRoomForLargeReport(worker);
    var earlier = waitForRoom(worker, 100_000);
    var later = waitForRoom(worker, 50_000);

    earlier.cancel();

    assertThat(later.unreadDemand()).isEqualTo(1);
  }

  /** A report call that the server reads and that holds the room of a large report. */
  private static ReportCall holdRoomForLargeReport(ReportingWorker worker) throws Exception {
    var probe = worker.dispatch();
    var call = worker.openReportCall();
    call.sendMetadata(
        worker
            .metadata(probe.request())
            .setReportLengthBytes(largeReport(probe.request()).getSerializedSize()));
    return call;
  }

  /** A report call whose metadata states a probe report of this length. */
  private static ReportCall waitForRoom(ReportingWorker worker, long reportLengthBytes)
      throws Exception {
    var call = worker.openReportCall();
    call.sendMetadata(
        worker.metadata(worker.dispatch().request()).setReportLengthBytes(reportLengthBytes));
    return call;
  }

  /** A report of about 140,000 bytes: each keyframe takes the widest encoding twice. */
  private static ProbeAttemptReport largeReport(ProbeRequest request) {
    var widestDeltas = Collections.nCopies(KEYFRAMES_OF_LARGE_REPORT, Long.MIN_VALUE);
    return invalidMedia(request)
        .addAllKeyframePresentationTimeDeltas(widestDeltas)
        .addAllKeyframeDecodeTimeDeltas(widestDeltas)
        .build();
  }

  /** A report with as many keyframes and measured keyframe intervals as the limits allow. */
  private static ProbeAttemptReport reportAtBothLimits(ProbeRequest request) {
    return withKeyframes(invalidMedia(request), LIMITS.keyframeLimit())
        .addStreamMeasurementIntervals(intervals(0, 3))
        .addStreamMeasurementIntervals(intervals(1, 3))
        .build();
  }

  private static ProbeAttemptReport.Builder withKeyframes(
      ProbeAttemptReport.Builder report, int keyframes) {
    return report
        .addAllKeyframePresentationTimeDeltas(Collections.nCopies(keyframes, 90_000L))
        .addAllKeyframeDecodeTimeDeltas(Collections.nCopies(keyframes, 90_000L));
  }

  /** One stream with this many keyframe intervals, each of 1,000 bytes in one packet. */
  private static StreamMeasurementIntervals intervals(int streamIndex, int intervals) {
    return StreamMeasurementIntervals.newBuilder()
        .setStreamIndex(streamIndex)
        .addAllKeyframeIntervalBytes(Collections.nCopies(intervals, 1_000))
        .addAllKeyframeIntervalPacketCounts(Collections.nCopies(intervals, 1))
        .build();
  }
}
