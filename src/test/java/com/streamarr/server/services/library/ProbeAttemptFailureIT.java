package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.task.ProbeAttemptFailure;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeState;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeCancelledException;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Probe attempt failures")
class ProbeAttemptFailureIT extends AbstractProbeSchedulerIntegrationTest {

  private static final Instant FAILED_AT = Instant.parse("2026-09-23T12:00:00Z");

  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private ProbeTaskRequests probeTaskRequests;

  @Test
  @DisplayName(
      "Should record why the attempt failed and keep the probe scheduled when the source is"
          + " unreadable")
  void shouldRecordWhyTheAttemptFailedAndKeepTheProbeScheduledWhenTheSourceIsUnreadable()
      throws Exception {
    var request = requestUnchangedFiles(1).getFirst();
    var client =
        runOnce(
            new ProbeExecutionException(
                ItemFailureReason.SOURCE_INACCESSIBLE, "Worker could not read the source"));

    assertThat(stateOf(request).failure())
        .hasValueSatisfying(
            failure -> {
              assertThat(failure.reason()).isEqualTo(ItemFailureReason.SOURCE_INACCESSIBLE);
              assertThat(failure.detail()).isEqualTo("Worker could not read the source");
            });
    assertThat(stateOf(request).stored()).isEmpty();
    assertThat(client.getScheduledExecution(instanceOf(request)))
        .hasValueSatisfying(pending -> assertThat(pending.getConsecutiveFailures()).isOne());
  }

  @Test
  @DisplayName("Should record an unexpected failure as temporary when the attempt throws")
  void shouldRecordAnUnexpectedFailureAsTemporaryWhenTheAttemptThrows() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();

    runOnce(new IllegalStateException("unexpected"));

    assertThat(stateOf(request).failure())
        .hasValueSatisfying(
            failure -> assertThat(failure.reason()).isEqualTo(ItemFailureReason.TEMPORARY));
  }

  @Test
  @DisplayName("Should record no failure and keep retrying when the attempt is cancelled")
  void shouldRecordNoFailureAndKeepRetryingWhenTheAttemptIsCancelled() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();

    var client = runOnce(new ProbeCancelledException("Worker cancelled the probe"));

    assertThat(stateOf(request).failure()).isEmpty();
    assertThat(client.getScheduledExecution(instanceOf(request))).isPresent();
  }

  @Test
  @DisplayName("Should clear the recorded failure when an outcome is stored for the same inputs")
  void shouldClearTheRecordedFailureWhenAnOutcomeIsStoredForTheSameInputs() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();
    assertThat(outcomes.recordProbeFailure(request.mediaFileId(), request.inputs(), failure()))
        .isTrue();

    outcomes.publish(
        ProbePublication.builder()
            .mediaFileId(request.mediaFileId())
            .snapshot(request.snapshot())
            .probeVersion(request.probeVersion())
            .outcome(
                ProbeFixture.completeProbe(
                    MediaProbe.builder()
                        .duration(Duration.ofMinutes(90))
                        .videoCodec("h264")
                        .width(1920)
                        .height(1080)
                        .build()))
            .build());

    assertThat(stateOf(request).failure()).isEmpty();
    assertThat(stateOf(request).stored())
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.inputs()).isEqualTo(request.inputs());
              assertThat(stored.error()).isEmpty();
            });
  }

  @Test
  @DisplayName("Should keep the recorded failure when the same inputs are requested again")
  void shouldKeepTheRecordedFailureWhenTheSameInputsAreRequestedAgain() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();
    outcomes.recordProbeFailure(request.mediaFileId(), request.inputs(), failure());

    probeTaskRequests.request(request);

    assertThat(stateOf(request).failure()).contains(failure());
  }

  @Test
  @DisplayName("Should clear the recorded failure when a request carries different inputs")
  void shouldClearTheRecordedFailureWhenARequestCarriesDifferentInputs() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();
    outcomes.recordProbeFailure(request.mediaFileId(), request.inputs(), failure());
    var changed = changedSnapshot(request);

    probeTaskRequests.request(changed);

    assertThat(stateOf(request).requested()).contains(changed.inputs());
    assertThat(stateOf(request).failure()).isEmpty();
  }

  @Test
  @DisplayName("Should not record a failure for inputs that are no longer requested")
  void shouldNotRecordAFailureForInputsThatAreNoLongerRequested() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();
    probeTaskRequests.request(changedSnapshot(request));

    var recorded = outcomes.recordProbeFailure(request.mediaFileId(), request.inputs(), failure());

    assertThat(recorded).isFalse();
    assertThat(stateOf(request).failure()).isEmpty();
  }

  private SchedulerClient runOnce(RuntimeException failure) throws InterruptedException {
    var completions = new CountDownLatch(1);
    var client =
        startScheduler(
            probeExecution.toBuilder()
                .producer(
                    _ -> {
                      throw failure;
                    })
                .build(),
            countingCompletions(completions));
    assertThat(completions.await(10, TimeUnit.SECONDS)).isTrue();
    return client;
  }

  private ProbeState stateOf(ProbeTaskRequest request) {
    return outcomes.findProbeStates(List.of(request.mediaFileId())).getFirst();
  }

  private static ProbeAttemptFailure failure() {
    return ProbeAttemptFailure.builder()
        .reason(ItemFailureReason.SOURCE_INACCESSIBLE)
        .detail("Worker could not read the source")
        .failedAt(FAILED_AT)
        .build();
  }

  private static ProbeTaskRequest changedSnapshot(ProbeTaskRequest request) {
    return request.toBuilder()
        .snapshot(
            new SourceFileSnapshot(
                request.snapshot().size() + 1,
                request.snapshot().modifiedAt().plus(1, ChronoUnit.SECONDS)))
        .probeVersion(ProbeVersion.CURRENT)
        .build();
  }
}
