package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.ReportingWorker.invalidMedia;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.services.streaming.remote.WorkerSessionServerConfiguration.WorkerSessionServerConfigurationBuilder;
import com.streamarr.transcode.v1.BitrateScanChunk;
import com.streamarr.transcode.v1.ProbePriority;
import com.streamarr.transcode.v1.ProbeRequest;
import io.grpc.Status;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Probe Report Deadline Tests")
@Timeout(30)
class ProbeReportDeadlineTest {

  private static final Duration SHORT_TIMEOUT = Duration.ofMillis(250);

  @ParameterizedTest(name = "{0}")
  @EnumSource(TimedProbe.class)
  @DisplayName(
      "Should stop the probe's timeout and send no cancel command when the first message of its"
          + " report call arrives before the timeout")
  void shouldStopProbesTimeoutAndSendNoCancelCommandWhenFirstMessageOfItsReportCallArrivesInTime(
      TimedProbe kind) throws Exception {
    var worker =
        ReportingWorker.connectedTo(
            kind.withShortTimeout(WorkerSessionServerConfiguration.builder()));
    var reported = worker.dispatch(kind.request(worker));
    var silent = worker.dispatch(kind.request(worker));
    var report = invalidMedia(reported.request()).build();
    var bytes = report.toByteString();
    var call = worker.openReportCall();
    call.sendMetadata(worker.metadata(reported.request()).setReportLengthBytes(bytes.size()));

    // The silent probe started later, so the reported probe's timeout has passed as well.
    assertThatThrownBy(() -> silent.outcome().get(5, TimeUnit.SECONDS))
        .hasCauseInstanceOf(TimeoutException.class);
    call.sendData(bytes);
    call.halfClose();

    assertThat(reported.outcome().get(1, TimeUnit.SECONDS)).isEqualTo(report);
    assertThat(worker.canceledProbeAttempts())
        .doesNotContain(reported.request().getProbeAttemptId());
  }

  private enum TimedProbe {
    PLAYBACK_PROBE {
      @Override
      WorkerSessionServerConfigurationBuilder withShortTimeout(
          WorkerSessionServerConfigurationBuilder configuration) {
        return configuration.probeTimeout(SHORT_TIMEOUT);
      }

      @Override
      ProbeRequest.Builder request(ReportingWorker worker) {
        return worker.probe().setPriority(ProbePriority.PROBE_PRIORITY_PLAYBACK);
      }
    },
    BACKGROUND_PROBE {
      @Override
      WorkerSessionServerConfigurationBuilder withShortTimeout(
          WorkerSessionServerConfigurationBuilder configuration) {
        return configuration.probeTimeout(SHORT_TIMEOUT);
      }

      @Override
      ProbeRequest.Builder request(ReportingWorker worker) {
        return worker.probe().setPriority(ProbePriority.PROBE_PRIORITY_BACKGROUND);
      }
    },
    KEYFRAME_PACKET_SCAN {
      @Override
      WorkerSessionServerConfigurationBuilder withShortTimeout(
          WorkerSessionServerConfigurationBuilder configuration) {
        return configuration.keyframeScanTimeout(SHORT_TIMEOUT);
      }

      @Override
      ProbeRequest.Builder request(ReportingWorker worker) {
        return worker.probe().setScanKeyframePackets(true);
      }
    },
    BITRATE_SCAN_CHUNK {
      @Override
      WorkerSessionServerConfigurationBuilder withShortTimeout(
          WorkerSessionServerConfigurationBuilder configuration) {
        return configuration.bitrateScanChunkTimeout(SHORT_TIMEOUT);
      }

      @Override
      ProbeRequest.Builder request(ReportingWorker worker) {
        return worker
            .probe()
            .setBitrateScanChunk(
                BitrateScanChunk.newBuilder()
                    .setMeasurementVersion(ReportingWorker.MEASUREMENT_VERSION)
                    .addMeasuredStreamIndexes(0)
                    .setTimeBaseNumerator(1)
                    .setTimeBaseDenominator(90_000)
                    .addAllPresentationTimeDeltas(List.of(0L, 90_000L))
                    .addAllDecodeTimeDeltas(List.of(0L, 90_000L))
                    .setFirstChunk(true));
      }
    };

    /** Shortens the timeout that bounds this kind of probe, and no other timeout. */
    abstract WorkerSessionServerConfigurationBuilder withShortTimeout(
        WorkerSessionServerConfigurationBuilder configuration);

    abstract ProbeRequest.Builder request(ReportingWorker worker);
  }

  @Test
  @DisplayName(
      "Should time out the probe when its report call is open and its first message has not"
          + " arrived")
  void shouldTimeOutProbeWhenItsReportCallIsOpenAndItsFirstMessageHasNotArrived() throws Exception {
    var worker =
        ReportingWorker.connectedTo(
            WorkerSessionServerConfiguration.builder().probeTimeout(SHORT_TIMEOUT));
    var probe = worker.dispatch();

    worker.openReportCall();

    assertThatThrownBy(() -> probe.outcome().get(5, TimeUnit.SECONDS))
        .hasCauseInstanceOf(TimeoutException.class);
  }

  @Test
  @DisplayName("Should time out the probe when the server refuses its report call's first message")
  void shouldTimeOutProbeWhenServerRefusesItsReportCallsFirstMessage() throws Exception {
    var worker =
        ReportingWorker.connectedTo(
            WorkerSessionServerConfiguration.builder().probeTimeout(SHORT_TIMEOUT));
    var probe = worker.dispatch();
    var call = worker.openReportCall();

    call.sendMetadata(
        worker
            .metadata(probe.request())
            .setWorkerSessionId(toProto(UUID.randomUUID()))
            .setReportLengthBytes(1));

    assertThat(call.refusal()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThatThrownBy(() -> probe.outcome().get(5, TimeUnit.SECONDS))
        .hasCauseInstanceOf(TimeoutException.class);
  }

  @Test
  @DisplayName(
      "Should read a report call, keep the timeout as its probe's outcome and free the probe's"
          + " unit of capacity when the report call crossed the cancel command of a timed-out"
          + " probe")
  void shouldReadReportCallAndKeepTimeoutAsOutcomeWhenItCrossedCancelCommandOfTimedOutProbe()
      throws Exception {
    var worker =
        ReportingWorker.connectedTo(
            WorkerSessionServerConfiguration.builder().probeTimeout(SHORT_TIMEOUT),
            ReportingWorker.capacities().setBackgroundProbeCapacity(1));
    var probe = worker.dispatch();
    assertThatThrownBy(() -> probe.outcome().get(5, TimeUnit.SECONDS))
        .hasCauseInstanceOf(TimeoutException.class);
    var whileTheWorkerStopsTheProbe = worker.tryDispatch();
    var report = invalidMedia(probe.request()).build();

    // The worker opened the report call before the cancel command reached it.
    var call = worker.report(probe.request(), report);

    assertThat(whileTheWorkerStopsTheProbe)
        .isEqualTo(new ProbeDispatch.Refused(ProbeRefusal.WORKERS_BUSY));
    assertThat(call.answer().get(1, TimeUnit.SECONDS).getAcceptedLengthBytes())
        .isEqualTo(report.getSerializedSize());
    assertThatThrownBy(() -> probe.outcome().get(1, TimeUnit.SECONDS))
        .hasCauseInstanceOf(TimeoutException.class);
    assertThat(worker.tryDispatch()).isInstanceOf(ProbeDispatch.Dispatched.class);
    assertThat(worker.isConnected()).isTrue();
  }

  @Test
  @DisplayName(
      "Should keep the worker session when the first message of a report call that crossed the"
          + " cancel command arrives within the cancellation timeout")
  void shouldKeepWorkerSessionWhenFirstMessageOfReportCallThatCrossedCancelCommandArrivesInTime()
      throws Exception {
    var worker =
        ReportingWorker.connectedTo(
            WorkerSessionServerConfiguration.builder().probeCancellationTimeout(SHORT_TIMEOUT));
    var silentWorker = worker.anotherWorker();
    var probe = worker.dispatch();
    var ignored = silentWorker.dispatch();
    var report = invalidMedia(probe.request()).build();
    var bytes = report.toByteString();
    probe.outcome().cancel(true);
    ignored.outcome().cancel(true);
    // The worker opened the report call before the cancel command reached it.
    var call = worker.openReportCall();
    call.sendMetadata(worker.metadata(probe.request()).setReportLengthBytes(bytes.size()));

    // The silent worker's probe was canceled later, so once the server has ended its session, the
    // wait for the first worker has passed as well.
    assertThat(silentWorker.sessionEnd().get(5, TimeUnit.SECONDS).getCode())
        .as("The server ends the session of a worker that answers a cancel command with nothing")
        .isEqualTo(Status.Code.DEADLINE_EXCEEDED);
    call.sendData(bytes);
    call.halfClose();

    assertThat(call.answer().get(1, TimeUnit.SECONDS).getAcceptedLengthBytes())
        .isEqualTo(bytes.size());
    assertThat(worker.sessionEnd()).isNotDone();
    assertThat(worker.isConnected()).isTrue();
  }
}
