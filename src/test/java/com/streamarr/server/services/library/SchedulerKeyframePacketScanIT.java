package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.TaskRepository;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.Task;
import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import com.streamarr.server.services.streaming.KeyframePacketScanner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

@Tag("IntegrationTest")
@DisplayName("Keyframe packet scan scheduling")
class SchedulerKeyframePacketScanIT extends AbstractProbeSchedulerIntegrationTest {

  private static final ProbeOutcome.Success PACKET_SCAN_REQUIRED =
      success(
          new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX));
  private static final KeyframeKnowledge SCANNED =
      new KeyframeKnowledge.Known(
          KeyframePositions.builder()
              .streamIndex(0)
              .timeBaseNumerator(1)
              .timeBaseDenominator(90_000)
              .source(KeyframePositionSource.PACKET_SCAN)
              .keyframes(List.of(new KeyframePosition(0, 0)))
              .build());

  @Autowired private ProbeTaskRequests scheduling;
  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private ProbeSchedulingProperties probeScheduling;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private TaskRepository probeTasks;
  @Autowired private DSLContext dsl;

  private final BlockingQueue<ExecutionComplete> scanCompletions = new LinkedBlockingQueue<>();

  @Test
  @DisplayName("Should keep the scan scheduled when the server restarts")
  void shouldKeepTheScanScheduledWhenTheServerRestarts() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();
    var probed = new CountDownLatch(1);
    startScheduler(packetScanRequiredProbe(), countingCompletions(probed));
    assertThat(probed.await(30, TimeUnit.SECONDS)).isTrue();
    stopScheduler();
    assertThat(scheduledScans()).as("The probe's publication schedules the scan").isOne();

    startScheduler(
        List.of(scanTask(_ -> success(SCANNED), Clock.systemUTC())),
        recordingScanCompletions(),
        Clock.systemUTC());

    assertThat(scanCompletions.poll(30, TimeUnit.SECONDS)).isNotNull();
    assertThat(storedKeyframes(request)).isEqualTo(SCANNED);
    assertThat(scheduledScans()).isZero();
  }

  @Test
  @DisplayName("Should scan the newer outcome again when the source changes during a scan")
  void shouldScanTheNewerOutcomeAgainWhenTheSourceChangesDuringAScan() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();
    packetScanRequiredProbe().execute(request);
    var scanning = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var scans = new AtomicInteger();
    KeyframePacketScanner scanner =
        _ -> {
          if (scans.incrementAndGet() == 1) {
            scanning.countDown();
            awaitRelease(release);
          }

          return success(SCANNED);
        };
    startScheduler(
        List.of(scanTask(scanner, Clock.systemUTC())),
        recordingScanCompletions(),
        Clock.systemUTC());
    assertThat(scanning.await(30, TimeUnit.SECONDS)).isTrue();

    var changed = changeSource(request);
    scheduling.request(changed);
    packetScanRequiredProbe().execute(changed);
    release.countDown();

    assertThat(scanCompletions.poll(30, TimeUnit.SECONDS)).isNotNull();
    assertThat(scanCompletions.poll(30, TimeUnit.SECONDS)).isNotNull();
    assertThat(scans).hasValue(2);
    assertThat(reader.find(request.mediaFileId()))
        .hasValueSatisfying(
            stored -> {
              assertThat(stored.snapshot()).isEqualTo(changed.snapshot());
              assertThat(stored.outcome()).isEqualTo(success(SCANNED));
            });
    assertThat(scheduledScans()).isZero();
  }

  @Test
  @DisplayName("Should end the scan without storing positions when the source changes during it")
  void shouldEndTheScanWithoutStoringPositionsWhenTheSourceChangesDuringIt() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();
    packetScanRequiredProbe().execute(request);

    startScheduler(
        List.of(
            scanTask(
                _ -> {
                  changeSource(request);
                  return success(SCANNED);
                },
                Clock.systemUTC())),
        recordingScanCompletions(),
        Clock.systemUTC());

    assertThat(scanCompletions.poll(30, TimeUnit.SECONDS)).isNotNull();
    assertThat(storedKeyframes(request)).isEqualTo(PACKET_SCAN_REQUIRED.keyframes());
    assertThat(scheduledScans())
        .as("The changed source gets a new probe, which asks for its own scan")
        .isZero();
  }

  @Test
  @DisplayName("Should keep the scan pending for the retry delay when every worker is busy")
  void shouldKeepTheScanPendingForTheRetryDelayWhenEveryWorkerIsBusy() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();
    packetScanRequiredProbe().execute(request);
    var clock =
        new MutableClock(
            new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)));

    startScheduler(
        List.of(
            scanTask(
                _ -> {
                  throw new ProbeWorkersBusyException();
                },
                clock)),
        recordingScanCompletions(),
        clock);

    var deferred = scanCompletions.poll(30, TimeUnit.SECONDS);
    assertThat(deferred).isNotNull();
    assertThat(deferred.getResult()).isEqualTo(ExecutionComplete.Result.OK);
    assertThat(pendingScanExecutionTime())
        .isEqualTo(deferred.getTimeDone().plus(probeScheduling.busyWorkerRetryDelay()));
    assertThat(storedKeyframes(request)).isEqualTo(PACKET_SCAN_REQUIRED.keyframes());
  }

  private ProbeExecution packetScanRequiredProbe() {
    var producer = new FakeFfprobeService();
    producer.answerWith(PACKET_SCAN_REQUIRED);
    return probeExecution.toBuilder().producer(producer).build();
  }

  private Task<?> scanTask(KeyframePacketScanner scanner, Clock clock) {
    var execution =
        KeyframePacketScanExecution.builder()
            .reader(reader)
            .scanner(scanner)
            .fileSystem(FileSystems.getDefault())
            .outcomes(outcomes)
            .properties(probeScheduling)
            .build();
    var completion =
        KeyframePacketScanCompletion.builder()
            .outcomes(outcomes)
            .transactionManager(transactionManager)
            .clock(clock)
            .properties(probeScheduling)
            .probeTasks(probeTasks)
            .build();
    return KeyframePacketScanTask.create(execution, completion, clock);
  }

  private AbstractSchedulerListener recordingScanCompletions() {
    return new AbstractSchedulerListener() {
      @Override
      public void onExecutionComplete(ExecutionComplete executionComplete) {
        if (executionComplete
            .getExecution()
            .taskInstance
            .getTaskName()
            .equals(KeyframePacketScanTask.NAME)) {
          scanCompletions.add(executionComplete);
        }
      }
    };
  }

  private KeyframeKnowledge storedKeyframes(ProbeTaskRequest request) {
    return outcomes.findKeyframeKnowledge(request.mediaFileId()).orElseThrow();
  }

  private int scheduledScans() {
    return dsl.fetchCount(
        DSL.table("scheduled_tasks"),
        DSL.field("task_name", String.class).eq(KeyframePacketScanTask.NAME));
  }

  private Instant pendingScanExecutionTime() {
    return dsl.select(DSL.field("execution_time", Instant.class))
        .from(DSL.table("scheduled_tasks"))
        .where(DSL.field("task_name", String.class).eq(KeyframePacketScanTask.NAME))
        .and(DSL.field("picked", Boolean.class).isFalse())
        .and(DSL.coalesce(DSL.field("consecutive_failures", Integer.class), 0).eq(0))
        .fetchSingle()
        .value1();
  }

  private static ProbeTaskRequest changeSource(ProbeTaskRequest request) {
    try {
      var path = FilepathCodec.decode(request.filepathUri());
      Files.write(path, new byte[] {9}, StandardOpenOption.APPEND);
      var attributes = Files.readAttributes(path, BasicFileAttributes.class);
      return request.toBuilder()
          .snapshot(
              new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant()))
          .build();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static void awaitRelease(CountDownLatch release) {
    try {
      assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private static ProbeOutcome.Success success(KeyframeKnowledge keyframes) {
    return new ProbeOutcome.Success(
        ProbeContainer.builder().build(),
        List.of(StreamInfo.builder().codecType("video").build()),
        keyframes);
  }
}
