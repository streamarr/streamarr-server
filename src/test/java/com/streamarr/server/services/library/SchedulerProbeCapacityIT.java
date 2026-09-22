package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.ScheduledExecution;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.streamarr.server.config.LibraryWatcherProperties;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.streaming.FfprobeService;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Configured probe concurrency")
class SchedulerProbeCapacityIT extends AbstractProbeSchedulerIntegrationTest {

  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private MediaFileRepository mediaFiles;
  @Autowired private LibraryWatcherProperties watcherProperties;

  private final BlockedProducer producer = new BlockedProducer();

  @AfterEach
  void releaseProducer() {
    producer.release();
  }

  @Test
  @DisplayName(
      "Should limit concurrent probes when more requests are due than the configured capacity")
  void shouldLimitConcurrentProbesWhenMoreRequestsAreDueThanTheConfiguredCapacity()
      throws Exception {
    var requests = requestUnchangedFiles(6);

    var firstBatchSubmitted = new CompletableFuture<Void>();
    var allExecutionsFinished = new CountDownLatch(requests.size());
    var listener =
        new AbstractSchedulerListener() {
          @Override
          public void onSchedulerEvent(SchedulerEventType type) {
            if (type == SchedulerEventType.RAN_EXECUTE_DUE) {
              firstBatchSubmitted.complete(null);
            }
          }

          @Override
          public void onExecutionComplete(ExecutionComplete executionComplete) {
            allExecutionsFinished.countDown();
          }
        };
    var execution =
        ProbeExecution.builder()
            .mediaFiles(mediaFiles)
            .reader(reader)
            .producer(producer)
            .fileSystem(FileSystems.getDefault())
            .outcomes(outcomes)
            .build();
    var client = startScheduler(execution, listener);

    try {
      firstBatchSubmitted.get(15, TimeUnit.SECONDS);
      var firstBatch =
          requests.stream()
              .filter(
                  request ->
                      client
                          .getScheduledExecution(instanceOf(request))
                          .filter(ScheduledExecution::isPicked)
                          .isPresent())
              .toList();
      assertThat(firstBatch).isNotEmpty();
      for (var request : firstBatch) {
        producer.awaitStarted(FilepathCodec.decode(request.filepathUri()));
      }
    } finally {
      producer.release();
    }

    assertThat(allExecutionsFinished.await(15, TimeUnit.SECONDS)).isTrue();
    assertPublished(requests);
    for (var request : requests) {
      assertThat(client.getScheduledExecution(instanceOf(request))).isEmpty();
    }

    assertThat(producer.peakConcurrency())
        .as("Configured capacity of 2 must limit active probe producers")
        .isLessThanOrEqualTo(2);
  }

  @Test
  @DisplayName("Should probe unchanged files without waiting a quiet period for each file")
  void shouldProbeUnchangedFilesWithoutWaitingAQuietPeriodForEachFile() throws Exception {
    var quietPeriod = Duration.ofSeconds(watcherProperties.stabilizationPeriodSeconds());
    assertThat(quietPeriod).isPositive();
    var requests = requestUnchangedFiles(6);

    var allExecutionsFinished = new CountDownLatch(requests.size());
    var listener = countingCompletions(allExecutionsFinished);
    var started = System.nanoTime();
    startScheduler(probeExecution.toBuilder().producer(new FakeFfprobeService()).build(), listener);

    var finished = allExecutionsFinished.await(quietPeriod.toMillis(), TimeUnit.MILLISECONDS);
    var elapsed = Duration.ofNanos(System.nanoTime() - started);

    assertThat(finished)
        .as(
            "%d unchanged files at capacity 2 finished=%s after %s; a quiet period of %s per"
                + " file would take at least %s",
            requests.size(), finished, elapsed, quietPeriod, quietPeriod.multipliedBy(3))
        .isTrue();
    assertPublished(requests);
  }

  private static final class BlockedProducer implements FfprobeService {

    private final FakeFfprobeService delegate = new FakeFfprobeService();
    private final CountDownLatch release = new CountDownLatch(1);
    private final ConcurrentHashMap<Path, CompletableFuture<Void>> started =
        new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();

    @Override
    public ProbeOutcome probe(ProbeExecutionRequest request) {
      var path = request.sourcePath();
      peak.accumulateAndGet(active.incrementAndGet(), Math::max);
      started.computeIfAbsent(path, _ -> new CompletableFuture<>()).complete(null);
      try {
        release.await();
        return delegate.probe(request);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new ProbeExecutionException(exception);
      } finally {
        active.decrementAndGet();
      }
    }

    private void awaitStarted(Path path) throws Exception {
      started.computeIfAbsent(path, _ -> new CompletableFuture<>()).get(15, TimeUnit.SECONDS);
    }

    private void release() {
      release.countDown();
    }

    private int peakConcurrency() {
      return peak.get();
    }
  }
}
