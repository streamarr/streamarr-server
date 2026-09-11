package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbeClaim;
import com.streamarr.server.domain.task.ProbeRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fakes.FakeFileProcessingTaskRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.task.FileProcessingTaskCoordinator;
import com.streamarr.server.support.LogCapture;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

@Tag("UnitTest")
@DisplayName("Probe task dispatcher failure observation tests")
class ProbeTaskDispatcherFailureObservationTest {

  @TempDir Path directory;

  @Test
  @DisplayName(
      "Should report the retry persistence cause when scheduling a failed probe retry fails")
  void shouldReportTheRetryPersistenceCauseWhenSchedulingAFailedProbeRetryFails() throws Exception {
    var clock = new MutableClock();
    var retryFailureCause = "retry storage connection refused: " + UUID.randomUUID();
    var repository =
        UnavailableRetryRepository.builder()
            .failure(
                new DataAccessResourceFailureException(
                    "Retry write failed", new IOException(retryFailureCause)))
            .build();
    var coordinator = new FileProcessingTaskCoordinator(repository, clock, Duration.ofSeconds(60));
    var path = Files.writeString(directory.resolve("movie.mkv"), "media");
    var request =
        ProbeRequest.builder()
            .mediaFileId(UUID.randomUUID())
            .libraryId(UUID.randomUUID())
            .filepathUri(FilepathCodec.encode(path))
            .snapshot(
                new SourceFileSnapshot(
                    Files.size(path), Files.getLastModifiedTime(path).toInstant()))
            .probeVersion(ProbeVersion.CURRENT)
            .build();
    var claim =
        ProbeClaim.builder()
            .taskId(UUID.randomUUID())
            .claimId(UUID.randomUUID())
            .request(request)
            .leaseExpiresAt(clock.instant().plusSeconds(60))
            .build();
    repository.available.add(claim);
    var execution = new AtomicReference<Thread>();
    var producerEntered = new CountDownLatch(1);
    var producerFailure =
        new ProbeExecutionException(new IOException("probe transport unavailable"));

    try (var logs = captureAllLogs();
        var dispatcher =
            ProbeTaskDispatcher.builder()
                .coordinator(coordinator)
                .reader(new PersistedProbeReader(_ -> Optional.empty()))
                .producer(
                    _ -> {
                      execution.set(Thread.currentThread());
                      producerEntered.countDown();
                      throw producerFailure;
                    })
                .fileSystem(FileSystems.getDefault())
                .stabilityChecker(_ -> true)
                .build()) {
      assertThatCode(dispatcher::dispatch).doesNotThrowAnyException();
      assertThat(producerEntered.await(5, TimeUnit.SECONDS))
          .as("the claimed probe reaches the controlled producer")
          .isTrue();
      assertThat(execution.get().join(Duration.ofSeconds(5))).isTrue();
      synchronized (logs.appender()) {
        var loggedCauses =
            logs.events().stream()
                .filter(event -> event.getThrowableProxy() != null)
                .map(event -> ThrowableProxyUtil.asString(event.getThrowableProxy()))
                .toList();
        assertThat(loggedCauses).anyMatch(cause -> cause.contains("probe transport unavailable"));
        assertThat(loggedCauses)
            .as("the retry persistence failure must remain diagnosable after execution ends")
            .anyMatch(cause -> cause.contains(retryFailureCause));
      }
    }
  }

  private static LogCapture captureAllLogs() {
    var logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    return new LogCapture(logger, appender);
  }

  private static class UnavailableRetryRepository extends FakeFileProcessingTaskRepository {

    private final ConcurrentLinkedQueue<ProbeClaim> available = new ConcurrentLinkedQueue<>();
    private final DataAccessResourceFailureException failure;

    @Builder
    private UnavailableRetryRepository(DataAccessResourceFailureException failure) {
      this.failure = failure;
    }

    @Override
    public Optional<ProbeClaim> claimProbeTask(String instanceId, Instant leaseExpiresAt) {
      return Optional.ofNullable(available.poll());
    }

    @Override
    public boolean retryProbe(ProbeClaim claim, String message, Instant retryAt) {
      throw failure;
    }
  }
}
