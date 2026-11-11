package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.ReportingWorker.endedWithoutReport;
import static com.streamarr.server.services.streaming.remote.ReportingWorker.invalidMedia;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.services.streaming.remote.WorkerSessionServerConfiguration.WorkerSessionServerConfigurationBuilder;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.ProbeAttemptEndedWithoutReport;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeRequest;
import io.grpc.Status;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Probe Attempt Ended Without Report Tests")
@Timeout(30)
class ProbeAttemptEndedWithoutReportTest {

  private static final Duration SHORT_TIMEOUT = Duration.ofMillis(250);
  private static final ProbeDispatch BUSY = new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY);
  // Room for one probe report of 140,000 bytes, and for no second one of 100,000 bytes beside it.
  private static final ProbeReportLimits TIGHT_MEMORY =
      ProbeReportLimits.builder().lengthLimitBytes(150_000).memoryBudgetBytes(200_000).build();

  @Test
  @DisplayName(
      "Should fail the probe for a retry, as when its worker is lost, and free its unit of"
          + " capacity when the worker ends the attempt without a probe report before any report"
          + " call")
  void shouldFailProbeForRetryAndFreeItsUnitWhenWorkerEndsAttemptWithoutReportBeforeAnyReportCall()
      throws Exception {
    var worker = workerWithOneBackgroundUnit();
    var probe = worker.dispatch();
    var whileTheProbeRuns = worker.tryDispatch();

    worker.endWithoutReport(probe.request());

    assertThat(whileTheProbeRuns).isEqualTo(BUSY);
    assertThatFailedForRetry(probe.outcome());
    assertThat(worker.tryDispatch()).isInstanceOf(ProbeDispatch.Dispatched.class);
    assertThat(worker.sessionEnd()).isNotDone();
    assertThat(worker.isConnected()).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(LateReportCall.class)
  @DisplayName(
      "Should refuse a report call, as one for a probe attempt that the server does not own, when"
          + " its first message arrives after the worker ended the attempt without a probe report")
  void shouldRefuseReportCallWhenItsFirstMessageArrivesAfterWorkerEndedAttemptWithoutReport(
      LateReportCall late) throws Exception {
    var worker = workerWithOneBackgroundUnit();
    var probe = worker.dispatch();
    var call = late.openAndEndAttempt(worker, probe.request());

    call.sendMetadata(worker.metadata(probe.request()).setReportLengthBytes(1));

    assertThat(call.refusal()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThatFailedForRetry(probe.outcome());
    assertThat(worker.sessionEnd()).isNotDone();
  }

  private enum LateReportCall {
    OPENED_BEFORE_THE_EVENT {
      @Override
      ReportCall openAndEndAttempt(ReportingWorker worker, ProbeRequest request) throws Exception {
        var call = worker.openReportCall();
        worker.endWithoutReport(request);
        return call;
      }
    },
    OPENED_AFTER_THE_EVENT {
      @Override
      ReportCall openAndEndAttempt(ReportingWorker worker, ProbeRequest request) throws Exception {
        worker.endWithoutReport(request);
        return worker.openReportCall();
      }
    };

    abstract ReportCall openAndEndAttempt(ReportingWorker worker, ProbeRequest request)
        throws Exception;
  }

  @Test
  @DisplayName(
      "Should end the open report call once, fail the probe for a retry and free its unit of"
          + " capacity when the worker ends the attempt without a probe report while the server"
          + " reads the report call")
  void shouldEndOpenReportCallAndFailProbeForRetryWhenWorkerEndsAttemptWhileServerReadsReportCall()
      throws Exception {
    var worker = workerWithOneBackgroundUnit();
    var probe = worker.dispatch();
    var call = worker.openReportCall();
    call.sendMetadata(worker.metadata(probe.request()).setReportLengthBytes(8));
    call.sendData(ByteString.copyFrom(new byte[] {1, 2, 3}));
    var requestedBeforeTheEvent = call.requestedMessages();

    worker.endWithoutReport(probe.request());
    // The worker's own end of the report call arrives after its event.
    call.cancel();

    // The call's fake fails the test when the server ends one call twice.
    assertThat(call.refusal()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(call.requestedMessages()).isEqualTo(requestedBeforeTheEvent);
    assertThatFailedForRetry(probe.outcome());
    assertThat(worker.tryDispatch()).isInstanceOf(ProbeDispatch.Dispatched.class);
    assertThat(worker.sessionEnd()).isNotDone();
    assertThat(worker.isConnected()).isTrue();
  }

  @Test
  @DisplayName(
      "Should give the room of the ended report call to a waiting probe report when the worker"
          + " ends the attempt without a probe report")
  void shouldGiveRoomOfEndedReportCallToWaitingProbeReportWhenWorkerEndsAttemptWithoutReport()
      throws Exception {
    var worker = ReportingWorker.connectedTo(TIGHT_MEMORY);
    var held = worker.dispatch();
    var holder = worker.openReportCall();
    holder.sendMetadata(worker.metadata(held.request()).setReportLengthBytes(140_000));
    var waiting = worker.openReportCall();
    waiting.sendMetadata(
        worker.metadata(worker.dispatch().request()).setReportLengthBytes(100_000));
    var demandWhileWaiting = waiting.unreadDemand();

    worker.endWithoutReport(held.request());

    assertThat(demandWhileWaiting).isZero();
    assertThat(waiting.unreadDemand()).isEqualTo(1);
    assertThat(waiting.isOpen()).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(NotItsAttempt.class)
  @DisplayName(
      "Should keep counting the probe, fail nothing and keep every worker session when the event"
          + " names no probe attempt that the server started on its worker session")
  void shouldKeepCountingProbeAndKeepEveryWorkerSessionWhenEventNamesNoAttemptOfItsWorkerSession(
      NotItsAttempt named) throws Exception {
    var worker = workerWithOneBackgroundUnit();
    var other = worker.anotherWorker();
    var probe = worker.dispatch();

    named.send(worker, other, probe.request());

    assertThat(probe.outcome()).isNotDone();
    assertThat(worker.tryDispatch()).isEqualTo(BUSY);
    assertThat(worker.sessionEnd()).isNotDone();
    assertThat(other.sessionEnd()).isNotDone();
    assertThat(worker.isConnected()).isTrue();
    assertThat(other.isConnected()).isTrue();
  }

  private enum NotItsAttempt {
    A_PROBE_ATTEMPT_THAT_THE_SERVER_NEVER_STARTED {
      @Override
      void send(ReportingWorker worker, ReportingWorker other, ProbeRequest running) {
        worker.send(endedWithoutReport(toProto(UUID.randomUUID())));
      }
    },
    NO_PROBE_ATTEMPT {
      @Override
      void send(ReportingWorker worker, ReportingWorker other, ProbeRequest running) {
        worker.send(
            EstablishWorkerSessionRequest.newBuilder()
                .setProbeAttemptEndedWithoutReport(
                    ProbeAttemptEndedWithoutReport.getDefaultInstance())
                .build());
      }
    },
    A_PROBE_ATTEMPT_OF_ANOTHER_WORKERS_SESSION {
      @Override
      void send(ReportingWorker worker, ReportingWorker other, ProbeRequest running) {
        other.endWithoutReport(running);
      }
    };

    abstract void send(ReportingWorker worker, ReportingWorker other, ProbeRequest running);
  }

  @Test
  @DisplayName(
      "Should keep the probe of the worker's live session pending when the event arrives on the"
          + " worker session that the live session replaced")
  void shouldKeepProbeOfLiveSessionPendingWhenEventArrivesOnWorkerSessionThatLiveSessionReplaced()
      throws Exception {
    var worker = ReportingWorker.connectedTo(WorkerSessionServerConfiguration.builder());
    var request = worker.probe();
    worker.dispatch(request);
    worker.reconnect();
    var onTheLiveSession = worker.dispatch(request);

    worker.sendOnEarlierSession(endedWithoutReport(request.getProbeAttemptId()));

    assertThat(onTheLiveSession.outcome()).isNotDone();
    assertThat(worker.sessionEnd()).isNotDone();
    assertThat(worker.isConnected()).isTrue();
  }

  @Test
  @DisplayName(
      "Should keep the probe report as the probe's outcome and keep counting a later probe when"
          + " the event arrives after the server answered the attempt's report call")
  void shouldKeepReportAsOutcomeAndKeepCountingLaterProbeWhenEventArrivesAfterServerAnswered()
      throws Exception {
    var worker = workerWithOneBackgroundUnit();
    var probe = worker.dispatch();
    var report = invalidMedia(probe.request()).build();
    var call = worker.report(probe.request(), report);
    var later = worker.dispatch();

    worker.endWithoutReport(probe.request());

    assertThat(call.isAnswered()).isTrue();
    assertThat(probe.outcome().get(1, TimeUnit.SECONDS)).isEqualTo(report);
    assertThat(later.outcome()).isNotDone();
    assertThat(worker.tryDispatch()).isEqualTo(BUSY);
    assertThat(worker.sessionEnd()).isNotDone();
    assertThat(worker.isConnected()).isTrue();
  }

  @Test
  @DisplayName(
      "Should keep counting a later probe and keep the worker session when the event arrives after"
          + " the attempt's report call ended without the server's answer")
  void shouldKeepCountingLaterProbeWhenEventArrivesAfterReportCallEndedWithoutServersAnswer()
      throws Exception {
    var worker = workerWithOneBackgroundUnit();
    var probe = worker.dispatch();
    var call = worker.openReportCall();
    call.sendMetadata(worker.metadata(probe.request()).setReportLengthBytes(8));
    call.cancel();
    var later = worker.dispatch();

    worker.endWithoutReport(probe.request());

    assertThatFailedForRetry(probe.outcome());
    assertThat(later.outcome()).isNotDone();
    assertThat(worker.tryDispatch()).isEqualTo(BUSY);
    assertThat(worker.sessionEnd()).isNotDone();
    assertThat(worker.isConnected()).isTrue();
  }

  @Test
  @DisplayName(
      "Should keep counting the probe that took the freed unit and keep the worker session when"
          + " the worker ends one attempt without a probe report twice")
  void shouldKeepCountingProbeThatTookFreedUnitWhenWorkerEndsOneAttemptWithoutReportTwice()
      throws Exception {
    var worker = workerWithOneBackgroundUnit();
    var probe = worker.dispatch();
    worker.endWithoutReport(probe.request());
    var later = worker.dispatch();

    worker.endWithoutReport(probe.request());

    assertThat(later.outcome()).isNotDone();
    assertThat(worker.tryDispatch()).isEqualTo(BUSY);
    assertThat(worker.sessionEnd()).isNotDone();
    assertThat(worker.isConnected()).isTrue();
  }

  @Test
  @DisplayName(
      "Should keep the probe's unit of capacity after a cancel command until the worker's event,"
          + " and keep the cancellation as the probe's outcome")
  void shouldKeepProbesUnitAfterCancelCommandUntilWorkersEventAndKeepCancellationAsItsOutcome()
      throws Exception {
    var worker = workerWithOneBackgroundUnit();
    var probe = worker.dispatch();
    probe.outcome().cancel(true);
    var whileTheWorkerStopsTheProbe = worker.tryDispatch();

    worker.endWithoutReport(probe.request());

    assertThat(worker.canceledProbeAttempts()).containsExactly(probe.request().getProbeAttemptId());
    assertThat(whileTheWorkerStopsTheProbe).isEqualTo(BUSY);
    assertThat(probe.outcome()).isCancelled();
    assertThat(worker.tryDispatch()).isInstanceOf(ProbeDispatch.Dispatched.class);
    assertThat(worker.sessionEnd()).isNotDone();
    assertThat(worker.isConnected()).isTrue();
  }

  @Test
  @DisplayName(
      "Should keep the probe's unit of capacity after its timeout until the worker's event, and"
          + " keep the timeout as the probe's outcome")
  void shouldKeepProbesUnitAfterItsTimeoutUntilWorkersEventAndKeepTimeoutAsItsOutcome()
      throws Exception {
    var worker =
        workerWithOneBackgroundUnit(
            WorkerSessionServerConfiguration.builder().probeTimeout(SHORT_TIMEOUT));
    var probe = worker.dispatch();
    assertThatThrownBy(() -> probe.outcome().get(5, TimeUnit.SECONDS))
        .hasCauseInstanceOf(TimeoutException.class);
    var whileTheWorkerStopsTheProbe = worker.tryDispatch();

    worker.endWithoutReport(probe.request());

    assertThat(whileTheWorkerStopsTheProbe).isEqualTo(BUSY);
    assertThatThrownBy(() -> probe.outcome().get(1, TimeUnit.SECONDS))
        .hasCauseInstanceOf(TimeoutException.class);
    assertThat(worker.tryDispatch()).isInstanceOf(ProbeDispatch.Dispatched.class);
    assertThat(worker.sessionEnd()).isNotDone();
    assertThat(worker.isConnected()).isTrue();
  }

  @Test
  @DisplayName(
      "Should keep the worker session when the worker answers a cancel command with the event"
          + " within the cancellation timeout, and end the session of a worker that answers with"
          + " nothing")
  void shouldKeepWorkerSessionWhenWorkerAnswersCancelCommandWithEventWithinCancellationTimeout()
      throws Exception {
    var worker =
        ReportingWorker.connectedTo(
            WorkerSessionServerConfiguration.builder().probeCancellationTimeout(SHORT_TIMEOUT));
    var silentWorker = worker.anotherWorker();
    var probe = worker.dispatch();
    var ignored = silentWorker.dispatch();
    probe.outcome().cancel(true);

    worker.endWithoutReport(probe.request());
    ignored.outcome().cancel(true);
    // The silent worker's probe was canceled later, so the wait for the first worker has passed
    // as well.
    var silentWorkersSessionEnd = silentWorker.sessionEnd().get(5, TimeUnit.SECONDS);

    assertThat(silentWorkersSessionEnd.getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
    assertThat(silentWorker.isConnected()).isFalse();
    assertThat(worker.sessionEnd()).isNotDone();
    assertThat(worker.isConnected()).isTrue();
  }

  @Test
  @DisplayName(
      "Should send no cancel command and keep the worker session when the probe's caller cancels"
          + " it after the worker ended the attempt without a probe report")
  void shouldSendNoCancelCommandWhenProbesCallerCancelsItAfterWorkerEndedAttemptWithoutReport()
      throws Exception {
    var worker = workerWithOneBackgroundUnit();
    var probe = worker.dispatch();
    worker.endWithoutReport(probe.request());

    var canceled = probe.outcome().cancel(true);

    assertThat(canceled).isFalse();
    assertThat(worker.canceledProbeAttempts()).isEmpty();
    assertThat(worker.tryDispatch()).isInstanceOf(ProbeDispatch.Dispatched.class);
    assertThat(worker.sessionEnd()).isNotDone();
  }

  @Test
  @DisplayName(
      "Should keep counting the probe that took the freed unit when the worker answers a cancel"
          + " command that crossed its event with a second event")
  void shouldKeepCountingProbeThatTookFreedUnitWhenWorkerAnswersCancelCommandThatCrossedItsEvent()
      throws Exception {
    var worker = workerWithOneBackgroundUnit();
    var probe = worker.dispatch();
    probe.outcome().cancel(true);
    // The worker sent this event before the cancel command reached it.
    worker.endWithoutReport(probe.request());
    var later = worker.dispatch();

    // The worker has nothing left for the attempt, so it answers the cancel command at once.
    worker.endWithoutReport(probe.request());

    assertThat(probe.outcome()).isCancelled();
    assertThat(later.outcome()).isNotDone();
    assertThat(worker.tryDispatch()).isEqualTo(BUSY);
    assertThat(worker.sessionEnd()).isNotDone();
    assertThat(worker.isConnected()).isTrue();
  }

  private static ReportingWorker workerWithOneBackgroundUnit() throws Exception {
    return workerWithOneBackgroundUnit(WorkerSessionServerConfiguration.builder());
  }

  private static ReportingWorker workerWithOneBackgroundUnit(
      WorkerSessionServerConfigurationBuilder configuration) throws Exception {
    return ReportingWorker.connectedTo(
        configuration, ReportingWorker.capacities().setBackgroundProbeCapacity(1));
  }

  private static void assertThatFailedForRetry(Future<ProbeAttemptReport> outcome) {
    assertThatThrownBy(() -> outcome.get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isExactlyInstanceOf(ProbeExecutionException.class)
        .asInstanceOf(InstanceOfAssertFactories.type(ProbeExecutionException.class))
        .extracting(ProbeExecutionException::reason)
        .isEqualTo(ItemFailureReason.TEMPORARY);
  }
}
