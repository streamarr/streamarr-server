package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbeState;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeCancelledException;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("IntegrationTest")
@DisplayName("Probe attempt failures")
class ProbeAttemptFailureIT extends AbstractProbeSchedulerIntegrationTest {

  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private ProbeTaskRequests probeTaskRequests;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

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
  @DisplayName(
      "Should record the failure for the requested inputs when an attempt at older inputs fails")
  void shouldRecordTheFailureForTheRequestedInputsWhenAnAttemptAtOlderInputsFails()
      throws Exception {
    var request = requestUnchangedFiles(1).getFirst();
    var probing = new CountDownLatch(1);
    var release = new CompletableFuture<Void>();
    var olderAndRequestedAttempts = new CountDownLatch(2);
    startScheduler(
        probeExecution.toBuilder().producer(workerFailingAfter(probing, release)).build(),
        countingCompletions(olderAndRequestedAttempts));
    assertThat(probing.await(10, TimeUnit.SECONDS)).isTrue();
    var changed = changedSnapshot(request);
    probeTaskRequests.request(changed);
    makeUnreadable(FilepathCodec.decode(request.filepathUri()));

    release.complete(null);

    assertThat(olderAndRequestedAttempts.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(stateOf(request).requested()).contains(changed.inputs());
    assertThat(stateOf(request).failure())
        .as("the failure of the attempt at the requested inputs, not the worker's")
        .hasValueSatisfying(
            failure -> {
              assertThat(failure.reason()).isEqualTo(ItemFailureReason.SOURCE_INACCESSIBLE);
              assertThat(failure.detail()).isEqualTo("The server could not read the media source");
            });
  }

  @Test
  @DisplayName("Should stop retrying when the media file is deleted during a failing attempt")
  void shouldStopRetryingWhenTheMediaFileIsDeletedDuringAFailingAttempt() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();
    var probing = new CountDownLatch(1);
    var release = new CompletableFuture<Void>();
    var completions = new CountDownLatch(1);
    var client =
        startScheduler(
            probeExecution.toBuilder().producer(workerFailingAfter(probing, release)).build(),
            countingCompletions(completions));
    assertThat(probing.await(10, TimeUnit.SECONDS)).isTrue();
    mediaFileRepository.deleteById(request.mediaFileId());

    release.complete(null);

    assertThat(completions.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(client.getScheduledExecution(instanceOf(request))).isEmpty();
  }

  @Test
  @DisplayName("Should keep the attempt claimed when its failure reason cannot be saved")
  void shouldKeepTheAttemptClaimedWhenItsFailureReasonCannotBeSaved() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();

    try (var _ = rejectFailureReasons()) {
      var client =
          runOnce(
              new ProbeExecutionException(
                  ItemFailureReason.SOURCE_INACCESSIBLE, "Worker could not read the source"));

      assertThat(stateOf(request).failure()).isEmpty();
      assertThat(client.getScheduledExecution(instanceOf(request)))
          .hasValueSatisfying(
              pending -> {
                assertThat(pending.isPicked())
                    .as("claimed until db-scheduler finds the execution dead")
                    .isTrue();
                assertThat(pending.getConsecutiveFailures()).as("no backoff applied").isZero();
              });
    }
  }

  private AutoCloseable rejectFailureReasons() {
    jdbcTemplate.execute(
        """
        CREATE FUNCTION reject_probe_failure() RETURNS trigger AS $$
        BEGIN
          RAISE EXCEPTION 'simulated probe failure write failure';
        END
        $$ LANGUAGE plpgsql
        """);
    jdbcTemplate.execute(
        """
        CREATE TRIGGER reject_probe_failure BEFORE UPDATE ON media_file_probe_task_request
        FOR EACH ROW WHEN (NEW.failure_reason IS NOT NULL)
        EXECUTE FUNCTION reject_probe_failure()
        """);
    return () -> {
      jdbcTemplate.execute("DROP TRIGGER reject_probe_failure ON media_file_probe_task_request");
      jdbcTemplate.execute("DROP FUNCTION reject_probe_failure()");
    };
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
