package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.streamarr.server.config.LibraryWatcherProperties;
import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fakes.VirtualTimeSleeper;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.support.ControlledClockConfiguration;
import com.streamarr.server.support.ControlledQuietPeriodConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

@Tag("IntegrationTest")
@DisplayName("Probe quiet periods under controlled time")
@Import({ControlledClockConfiguration.class, ControlledQuietPeriodConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SchedulerProbeQuietPeriodIT extends AbstractProbeSchedulerIntegrationTest {

  @Autowired private VirtualTimeSleeper quietPeriodSleeper;
  @Autowired private FileStabilityChecker fileStabilityChecker;
  @Autowired private LibraryWatcherProperties watcherProperties;
  @Autowired private MutableClock clock;
  @Autowired private ProbeTaskRequests probeTaskRequests;
  @Autowired private ProbeSchedulingProperties probeScheduling;

  @Test
  @DisplayName("Should spend no quiet period when a batch of unchanged files is probed")
  void shouldSpendNoQuietPeriodWhenABatchOfUnchangedFilesIsProbed() throws Exception {
    var requests = requestUnchangedFiles(6);
    var allExecutionsFinished = new CountDownLatch(requests.size());
    var waitedBefore = quietPeriodSleeper.totalSlept();

    startScheduler(
        probeExecution.toBuilder().producer(new FakeFfprobeService()).build(),
        countingCompletions(allExecutionsFinished),
        clock);

    assertThat(allExecutionsFinished.await(15, TimeUnit.SECONDS)).isTrue();
    assertPublished(requests);
    assertThat(quietPeriodSleeper.totalSlept().minus(waitedBefore))
        .as("Virtual quiet-period time spent probing %d unchanged files", requests.size())
        .isZero();
  }

  @Test
  @DisplayName(
      "Should wait a quiet period outside probe slots when a source changes during its probe")
  void shouldWaitAQuietPeriodOutsideProbeSlotsWhenASourceChangesDuringItsProbe() throws Exception {
    var quietPeriod = Duration.ofSeconds(watcherProperties.stabilizationPeriodSeconds());
    var requests = requestUnchangedFiles(3);
    var changing = requests.getFirst();
    var changingSource = FilepathCodec.decode(changing.filepathUri());
    var copying = new AtomicBoolean(true);
    var changingProbes = new AtomicInteger();
    var fake = new FakeFfprobeService();
    FfprobeService producer =
        request -> {
          if (request.sourcePath().equals(changingSource)) {
            changingProbes.incrementAndGet();
            appendWhile(copying, changingSource);
          }

          return fake.probe(request);
        };
    var completed = ConcurrentHashMap.<String>newKeySet();

    var client =
        startScheduler(
            probeExecution.toBuilder().producer(producer).build(),
            recordingCompletions(completed),
            clock);

    await().atMost(Duration.ofSeconds(15)).until(() -> completed.size() == requests.size());
    assertThat(client.getScheduledExecution(instanceOf(changing)))
        .hasValueSatisfying(
            retry -> {
              // scheduled_tasks keeps microseconds; the controlled clock can carry nanoseconds.
              assertThat(retry.getExecutionTime())
                  .isCloseTo(clock.instant().plus(quietPeriod), within(1, ChronoUnit.MICROS));
              assertThat(retry.isPicked()).isFalse();
            });
    assertThat(changingProbes).hasValue(1);
    assertPublished(requests.subList(1, requests.size()));

    copying.set(false);
    var copied = snapshotOf(changingSource);
    clock.advance(quietPeriod);

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertThat(reader.find(changing.mediaFileId()))
                    .hasValueSatisfying(stored -> assertThat(stored.snapshot()).isEqualTo(copied)));
    assertThat(changingProbes).hasValue(2);
  }

  @Test
  @DisplayName(
      "Should keep the quiet period when a scan records the changed source during its probe")
  void shouldKeepTheQuietPeriodWhenAScanRecordsTheChangedSourceDuringItsProbe() throws Exception {
    var quietPeriod = Duration.ofSeconds(watcherProperties.stabilizationPeriodSeconds());
    var changing = requestUnchangedFiles(1).getFirst();
    var changingSource = FilepathCodec.decode(changing.filepathUri());
    var copying = new AtomicBoolean(true);
    var probing = new CountDownLatch(1);
    var scanned = new CountDownLatch(1);
    var changingProbes = new AtomicInteger();
    var fake = new FakeFfprobeService();
    FfprobeService producer =
        request -> {
          if (changingProbes.incrementAndGet() == 1) {
            appendWhile(copying, changingSource);
            probing.countDown();
            awaitSignal(scanned);
          }

          return fake.probe(request);
        };
    var completed = ConcurrentHashMap.<String>newKeySet();
    var client =
        startScheduler(
            probeExecution.toBuilder().producer(producer).build(),
            recordingCompletions(completed),
            clock);

    assertThat(probing.await(15, TimeUnit.SECONDS)).isTrue();
    probeTaskRequests.request(changing.toBuilder().snapshot(snapshotOf(changingSource)).build());
    scanned.countDown();

    await().atMost(Duration.ofSeconds(15)).until(() -> !completed.isEmpty());
    assertThat(client.getScheduledExecution(instanceOf(changing)))
        .hasValueSatisfying(
            retry -> {
              assertThat(retry.isPicked()).isFalse();
              assertThat(retry.getExecutionTime())
                  .isCloseTo(clock.instant().plus(quietPeriod), within(1, ChronoUnit.MICROS));
            });
    assertThat(changingProbes).hasValue(1);
  }

  @Test
  @DisplayName(
      "Should keep the busy-worker delay when a scan records a newer snapshot while workers are"
          + " busy")
  void shouldKeepTheBusyWorkerDelayWhenAScanRecordsANewerSnapshotWhileWorkersAreBusy()
      throws Exception {
    var changing = requestUnchangedFiles(1).getFirst();
    var changingSource = FilepathCodec.decode(changing.filepathUri());
    var probing = new CountDownLatch(1);
    var scanned = new CountDownLatch(1);
    FfprobeService busyWorkers =
        _ -> {
          probing.countDown();
          awaitSignal(scanned);
          throw new ProbeWorkersBusyException();
        };
    var deferred = new CountDownLatch(1);
    var client =
        startScheduler(
            probeExecution.toBuilder().producer(busyWorkers).build(),
            countingCompletions(deferred),
            clock);

    assertThat(probing.await(15, TimeUnit.SECONDS)).isTrue();
    Files.write(changingSource, new byte[] {9}, StandardOpenOption.APPEND);
    var scan = changing.toBuilder().snapshot(snapshotOf(changingSource)).build();
    probeTaskRequests.request(scan);
    scanned.countDown();

    assertThat(deferred.await(15, TimeUnit.SECONDS)).isTrue();
    assertThat(client.getScheduledExecution(instanceOf(changing)))
        .as("A newer request must not pull a busy-worker deferral earlier")
        .hasValueSatisfying(
            retry -> {
              assertThat(retry.isPicked()).isFalse();
              assertThat(retry.getData()).isEqualTo(scan);
              // scheduled_tasks keeps microseconds; the controlled clock can carry nanoseconds.
              assertThat(retry.getExecutionTime())
                  .isCloseTo(
                      clock.instant().plus(probeScheduling.busyWorkerRetryDelay()),
                      within(1, ChronoUnit.MICROS));
            });
  }

  @Test
  @DisplayName("Should keep a pending quiet period when a scan records a newer snapshot")
  void shouldKeepAPendingQuietPeriodWhenAScanRecordsANewerSnapshot() throws Exception {
    var changing = requestUnchangedFiles(1).getFirst();
    var changingSource = FilepathCodec.decode(changing.filepathUri());
    var copying = new AtomicBoolean(true);
    var fake = new FakeFfprobeService();
    FfprobeService producer =
        request -> {
          appendWhile(copying, changingSource);
          return fake.probe(request);
        };
    var completed = ConcurrentHashMap.<String>newKeySet();
    var client =
        startScheduler(
            probeExecution.toBuilder().producer(producer).build(),
            recordingCompletions(completed),
            clock);
    await().atMost(Duration.ofSeconds(15)).until(() -> !completed.isEmpty());
    var deadline = client.getScheduledExecution(instanceOf(changing)).orElseThrow();

    appendWhile(copying, changingSource);
    var scan = changing.toBuilder().snapshot(snapshotOf(changingSource)).build();
    probeTaskRequests.request(scan);

    assertThat(client.getScheduledExecution(instanceOf(changing)))
        .hasValueSatisfying(
            retry -> {
              assertThat(retry.getData()).isEqualTo(scan);
              assertThat(retry.getExecutionTime()).isEqualTo(deadline.getExecutionTime());
            });
  }

  @Test
  @DisplayName("Should spend one quiet period when the watcher waits for an unchanged file")
  void shouldSpendOneQuietPeriodWhenTheWatcherWaitsForAnUnchangedFile() throws Exception {
    var source = FilepathCodec.decode(requestUnchangedFiles(1).getFirst().filepathUri());
    var waitedBefore = quietPeriodSleeper.totalSlept();

    assertThat(fileStabilityChecker.waitForStability(source)).isTrue();

    assertThat(quietPeriodSleeper.totalSlept().minus(waitedBefore))
        .isEqualTo(Duration.ofSeconds(watcherProperties.stabilizationPeriodSeconds()));
  }

  private static AbstractSchedulerListener recordingCompletions(Set<String> completed) {
    return new AbstractSchedulerListener() {
      @Override
      public void onExecutionComplete(ExecutionComplete executionComplete) {
        completed.add(executionComplete.getExecution().taskInstance.getId());
      }
    };
  }

  private static void appendWhile(AtomicBoolean copying, Path source) {
    if (!copying.get()) {
      return;
    }

    try {
      Files.write(source, new byte[] {9}, StandardOpenOption.APPEND);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static void awaitSignal(CountDownLatch signal) {
    try {
      if (!signal.await(15, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Signal was not given within 15 seconds");
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  private static SourceFileSnapshot snapshotOf(Path source) throws IOException {
    return new SourceFileSnapshot(
        Files.size(source), Files.getLastModifiedTime(source).toInstant());
  }
}
