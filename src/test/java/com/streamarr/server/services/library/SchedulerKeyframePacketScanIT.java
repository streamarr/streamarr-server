package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.streamarr.server.config.KeyframePacketScanScheduler;
import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.server.exceptions.ProbeTimedOutException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import com.streamarr.server.services.streaming.KeyframePacketScanner;
import com.streamarr.server.support.UnstartedSchedulerConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

@TestPropertySource(properties = "PROBE_KEYFRAME_PACKET_SCAN_SCHEDULER_THREADS=1")
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
  @Autowired private DataSource dataSource;
  @Autowired private Environment environment;
  @Autowired private DSLContext dsl;
  @Autowired private MediaFileProbeTaskScheduler probeTaskScheduler;

  private final BlockingQueue<ExecutionComplete> scanCompletions = new LinkedBlockingQueue<>();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private KeyframePacketScanScheduler scanScheduler;

  @Test
  @DisplayName(
      "Should leave further scans pending when the configured scheduler ceiling is reached")
  void shouldLeaveFurtherScansPendingWhenConfiguredSchedulerCeilingIsReached() throws Exception {
    var requests = requestUnchangedFiles(3);
    for (var request : requests) {
      packetScanRequiredProbe().execute(request, ProbePriority.BACKGROUND);
    }

    var started = new LinkedBlockingQueue<ProbeExecutionRequest>();
    var release = new CountDownLatch(1);
    startScanScheduler(
        request -> {
          started.add(request);
          awaitRelease(release);
          return success(SCANNED);
        },
        Clock.systemUTC());

    try {
      assertThat(started.poll(10, TimeUnit.SECONDS)).isNotNull();
      assertThat(started.poll(2, TimeUnit.SECONDS)).isNull();
    } finally {
      release.countDown();
    }

    for (var _ : requests) {
      assertThat(scanCompletions.poll(30, TimeUnit.SECONDS)).isNotNull();
    }

    assertThat(requests)
        .allSatisfy(request -> assertThat(storedKeyframes(request)).isEqualTo(SCANNED));
    assertThat(scheduledScans()).isZero();
  }

  @Test
  @DisplayName("Should keep the scan scheduled when the server restarts")
  void shouldKeepTheScanScheduledWhenTheServerRestarts() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();
    var probed = new CountDownLatch(1);
    startScheduler(packetScanRequiredProbe(), countingCompletions(probed));
    assertThat(probed.await(30, TimeUnit.SECONDS)).isTrue();
    stopScheduler();
    assertThat(scheduledScans()).as("The probe's publication schedules the scan").isOne();

    startScanScheduler(_ -> success(SCANNED), Clock.systemUTC());

    assertThat(scanCompletions.poll(30, TimeUnit.SECONDS)).isNotNull();
    assertThat(storedKeyframes(request)).isEqualTo(SCANNED);
    assertThat(scheduledScans()).isZero();
  }

  @Test
  @DisplayName("Should scan the newer outcome again when the source changes during a scan")
  void shouldScanTheNewerOutcomeAgainWhenTheSourceChangesDuringAScan() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();
    packetScanRequiredProbe().execute(request, ProbePriority.BACKGROUND);
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
    startScanScheduler(scanner, Clock.systemUTC());
    assertThat(scanning.await(30, TimeUnit.SECONDS)).isTrue();

    var changed = changeSource(request);
    scheduling.request(changed);
    packetScanRequiredProbe().execute(changed, ProbePriority.BACKGROUND);
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
    packetScanRequiredProbe().execute(request, ProbePriority.BACKGROUND);

    startScanScheduler(
        _ -> {
          changeSource(request);
          return success(SCANNED);
        },
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
    packetScanRequiredProbe().execute(request, ProbePriority.BACKGROUND);
    var clock =
        new MutableClock(
            new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)));

    startScanScheduler(
        _ -> {
          throw new ProbeWorkersBusyException("All compatible workers are busy");
        },
        clock);

    var deferred = scanCompletions.poll(30, TimeUnit.SECONDS);
    assertThat(deferred).isNotNull();
    assertThat(deferred.getResult()).isEqualTo(ExecutionComplete.Result.OK);
    assertThat(pendingScanExecutionTime())
        .isEqualTo(deferred.getTimeDone().plus(probeScheduling.busyWorkerRetryDelay()));
    assertThat(storedKeyframes(request)).isEqualTo(PACKET_SCAN_REQUIRED.keyframes());
  }

  @Test
  @DisplayName("Should keep probing on every probe thread while a keyframe packet scan runs")
  void shouldKeepProbingOnEveryProbeThreadWhileAKeyframePacketScanRuns() throws Exception {
    var files = createUnchangedFiles(3);
    packetScanRequiredProbe().execute(files.getFirst(), ProbePriority.BACKGROUND);
    var scanning = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    KeyframePacketScanner blockedScanner =
        _ -> {
          scanning.countDown();
          awaitRelease(release);
          return success(SCANNED);
        };
    var probing = new CountDownLatch(2);
    var bothProbing = new CountDownLatch(1);
    var producer =
        new FakeFfprobeService() {
          @Override
          public ProbeOutcome probe(ProbeExecutionRequest request) {
            probing.countDown();
            if (probing.getCount() == 0) {
              bothProbing.countDown();
            }

            awaitRelease(release);
            return super.probe(request);
          }
        };
    producer.answerWith(PACKET_SCAN_REQUIRED);
    var probes = probeExecution.toBuilder().producer(producer).build();
    startScheduler(probes, countingCompletions(new CountDownLatch(0)));
    startScanScheduler(blockedScanner, Clock.systemUTC());
    assertThat(scanning.await(30, TimeUnit.SECONDS)).isTrue();

    files.stream().skip(1).forEach(scheduling::request);

    try {
      assertThat(bothProbing.await(10, TimeUnit.SECONDS))
          .as("Both probes run while the scan holds its slot")
          .isTrue();
    } finally {
      release.countDown();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scanFailures")
  @DisplayName("Should store unavailable keyframes and stop scanning when every scan fails")
  void shouldStoreUnavailableKeyframesAndStopScanningWhenEveryScanFails(
      String reason, Supplier<RuntimeException> failure) throws Exception {
    var request = requestUnchangedFiles(1).getFirst();
    packetScanRequiredProbe().execute(request, ProbePriority.BACKGROUND);
    var scans = new AtomicInteger();
    startScanScheduler(
        _ -> {
          scans.incrementAndGet();
          throw failure.get();
        },
        Clock.systemUTC());

    for (var attempt = 1; attempt <= 3; attempt++) {
      assertThat(scanCompletions.poll(30, TimeUnit.SECONDS)).isNotNull();
      retryPendingScansNow();
    }

    assertThat(scans).hasValue(3);
    assertThat(storedKeyframes(request))
        .isEqualTo(
            new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.PACKET_SCAN_FAILED));
    assertThat(scheduledScans()).isZero();
    assertThat(
            meterRegistry
                .get("streamarr.probes.keyframe_packet_scan_failures")
                .tag("reason", reason)
                .counter()
                .count())
        .isEqualTo(3);
  }

  private static Stream<Arguments> scanFailures() {
    return Stream.of(
        arguments(
            "timed_out",
            (Supplier<RuntimeException>) () -> new ProbeTimedOutException(new TimeoutException())),
        arguments(
            "contract_violation",
            (Supplier<RuntimeException>)
                () ->
                    new ProbeContractViolationException(
                        "Worker probe keyframe decode times do not increase")));
  }

  @Test
  @DisplayName(
      "Should scan a source again when it returns unchanged after its scan found it missing")
  void shouldScanASourceAgainWhenItReturnsUnchangedAfterItsScanFoundItMissing() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();
    packetScanRequiredProbe().execute(request, ProbePriority.BACKGROUND);
    var source = FilepathCodec.decode(request.filepathUri());
    var away = Files.move(source, source.resolveSibling(source.getFileName() + ".away"));
    startScanScheduler(_ -> success(SCANNED), Clock.systemUTC());
    assertThat(scanCompletions.poll(30, TimeUnit.SECONDS)).isNotNull();
    assertThat(storedKeyframes(request)).isEqualTo(PACKET_SCAN_REQUIRED.keyframes());

    Files.move(away, source);
    probeTaskScheduler.schedule(request.mediaFileId());

    assertThat(scanCompletions.poll(30, TimeUnit.SECONDS)).isNotNull();
    assertThat(storedKeyframes(request)).isEqualTo(SCANNED);
  }

  @Test
  @DisplayName("Should store no outcome when the packet scan the outcome needs cannot be requested")
  void shouldStoreNoOutcomeWhenThePacketScanTheOutcomeNeedsCannotBeRequested() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();
    var probe =
        packetScanRequiredProbe().toBuilder()
            .packetScans(
                _ -> {
                  throw new IllegalStateException("The scan table is unavailable");
                })
            .build();

    assertThatIllegalStateException()
        .isThrownBy(() -> probe.execute(request, ProbePriority.BACKGROUND));

    assertThat(reader.find(request.mediaFileId())).isEmpty();
    assertThat(outcomes.findKeyframeKnowledge(request.mediaFileId())).isEmpty();
  }

  private ProbeExecution packetScanRequiredProbe() {
    var producer = new FakeFfprobeService();
    producer.answerWith(PACKET_SCAN_REQUIRED);
    return probeExecution.toBuilder().producer(producer).build();
  }

  private void startScanScheduler(KeyframePacketScanner scanner, Clock clock) {
    var execution =
        KeyframePacketScanExecution.builder()
            .reader(reader)
            .scanner(scanner)
            .fileSystem(FileSystems.getDefault())
            .outcomes(outcomes)
            .meterRegistry(meterRegistry)
            .build();
    scanScheduler =
        KeyframePacketScanScheduler.builder()
            .schedulerProperties(UnstartedSchedulerConfiguration.schedulerProperties(environment))
            .customizer(probeSchedulerStarter().pickingCustomizer())
            .dataSource(dataSource)
            .transactionManager(transactionManager)
            .execution(execution)
            .outcomes(outcomes)
            .properties(probeScheduling)
            .clock(clock)
            .listeners(List.of(recordingScanCompletions()))
            .build();
    scanScheduler.start();
  }

  @AfterEach
  void stopScanScheduler() {
    if (scanScheduler != null) {
      scanScheduler.stop();
    }
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
        DSL.table(KeyframePacketScanScheduler.TABLE_NAME),
        DSL.field("task_name", String.class).eq(KeyframePacketScanTask.NAME));
  }

  // Stands in for the retry delay elapsing.
  private void retryPendingScansNow() {
    dsl.update(DSL.table(KeyframePacketScanScheduler.TABLE_NAME))
        .set(DSL.field("execution_time", Instant.class), Instant.now())
        .where(DSL.field("picked", Boolean.class).isFalse())
        .execute();
  }

  private Instant pendingScanExecutionTime() {
    return dsl.select(DSL.field("execution_time", Instant.class))
        .from(DSL.table(KeyframePacketScanScheduler.TABLE_NAME))
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
