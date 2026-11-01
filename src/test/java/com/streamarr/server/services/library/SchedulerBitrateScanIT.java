package com.streamarr.server.services.library;

import static com.streamarr.server.jooq.generated.tables.MediaFileBitrateScanChunk.MEDIA_FILE_BITRATE_SCAN_CHUNK;
import static com.streamarr.server.jooq.generated.tables.MediaFileStreamMeasurement.MEDIA_FILE_STREAM_MEASUREMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.streamarr.server.config.KeyframePacketScanScheduler;
import com.streamarr.server.config.ProbeSchedulingConfiguration;
import com.streamarr.server.config.ProbeSchedulingProperties;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.streaming.BitrateScanChunkOutcome;
import com.streamarr.server.domain.streaming.BitrateScanChunkRequest;
import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MeasuredChunkStream;
import com.streamarr.server.domain.streaming.MeasuredStream;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.task.BitrateScanTaskRequest;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.ProbeTimedOutException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.repositories.media.BitrateScanRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.streaming.BitrateScanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
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
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "PROBE_KEYFRAME_PACKET_SCAN_SCHEDULER_THREADS=1")
@Tag("IntegrationTest")
@DisplayName("Bitrate scan scheduling")
class SchedulerBitrateScanIT extends AbstractProbeSchedulerIntegrationTest {

  private static final String CHUNK_FAILURES_METRIC =
      "streamarr.probes.bitrate_scan_chunk_failures";
  private static final String FINISHED_SCANS_METRIC = "streamarr.probes.bitrate_scans_finished";
  private static final int VIDEO = 0;
  private static final int AUDIO = 1;
  private static final long TICKS_PER_KEYFRAME = 90_000;
  // One chunk holds at most 4,096 keyframe intervals, so these keyframes need two chunks.
  private static final int KEYFRAME_COUNT = 4_100;
  private static final long VIDEO_BYTES_PER_INTERVAL = 1_000;
  private static final long AUDIO_BYTES_PER_INTERVAL = 100;

  @Autowired private BitrateScanRepository bitrateScans;
  @Autowired private BitrateScanRequester bitrateScanRequester;
  @Autowired private MediaFileContainerInfoRepository outcomes;
  @Autowired private KeyframePacketScanExecution keyframePacketScanExecution;
  @Autowired private ProbeSchedulingProperties probeScheduling;
  @Autowired private DSLContext dsl;
  @Autowired private Serializer taskDataSerializer;

  private final BlockingQueue<ExecutionComplete> chunkCompletions = new LinkedBlockingQueue<>();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private KeyframePacketScanScheduler scanScheduler;

  @AfterEach
  void stopScanScheduler() {
    if (scanScheduler != null) {
      scanScheduler.stop();
    }
  }

  @ParameterizedTest(name = "chosen video bitrate={0}")
  @MethodSource("requestPriorities")
  @DisplayName(
      "Should request the scan at the priority of the chosen video's probed bitrate when a probe"
          + " stores an outcome that needs a scan")
  void shouldRequestTheScanAtThePriorityOfTheChosenVideosProbedBitrate(
      OptionalLong videoBitrate, int priority) throws Exception {
    var request = requestUnchangedFiles(1).getFirst();

    probe(copyableOutcome(videoBitrate)).execute(request, ProbePriority.BACKGROUND);

    assertThat(scheduledScanPriorities()).containsExactly(priority);
  }

  private static Stream<Arguments> requestPriorities() {
    return Stream.of(
        arguments(OptionalLong.of(8_000_000), 20), arguments(OptionalLong.empty(), 30));
  }

  @Test
  @DisplayName("Should report each waiting bitrate scan when scans wait in the scan table")
  void shouldReportEachWaitingBitrateScanWhenScansWaitInTheScanTable() throws Exception {
    var waiting = new SimpleMeterRegistry();
    new ProbeSchedulingConfiguration().bitrateScansWaitingMeter(dsl).bindTo(waiting);
    var files = requestUnchangedFiles(3);

    probe(copyableOutcome(OptionalLong.empty())).execute(files.get(0), ProbePriority.BACKGROUND);
    probe(copyableOutcome(OptionalLong.empty())).execute(files.get(1), ProbePriority.BACKGROUND);
    probe(packetScanRequired()).execute(files.get(2), ProbePriority.BACKGROUND);

    assertThat(waiting.get("streamarr.probes.bitrate_scans_waiting").gauge().value()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should request no scan when the probe stores an outcome without a copyable video")
  void shouldRequestNoScanWhenTheProbeStoresAnOutcomeWithoutACopyableVideo() throws Exception {
    var request = requestUnchangedFiles(1).getFirst();

    probe(withVideoCodec(copyableOutcome(OptionalLong.empty()), "mpeg2video"))
        .execute(request, ProbePriority.BACKGROUND);

    assertThat(scheduledScanPriorities()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should measure every stream across two chunks when the file has more keyframe intervals"
          + " than one chunk holds")
  void shouldMeasureEveryStreamAcrossTwoChunksWhenTheFileHasMoreIntervalsThanOneChunkHolds()
      throws Exception {
    var request = probedCopyableFile();
    var chunks = new CopyOnWriteArrayList<BitrateScanChunkRequest>();

    startScanScheduler(
        chunk -> {
          chunks.add(chunk);
          return measured(chunk);
        },
        Clock.systemUTC());
    awaitChunkCompletions(2);

    assertThat(chunks)
        .extracting(
            chunk -> chunk.keyframes().size(),
            BitrateScanChunkRequest::firstChunk,
            BitrateScanChunkRequest::end)
        .containsExactly(
            tuple(4_096, true, Optional.of(keyframe(4_096))), tuple(4, false, Optional.empty()));
    assertThat(chunks.getFirst().measuredStreamIndexes()).containsExactly(VIDEO, AUDIO);
    assertThat(outcomes.findStreamMeasurements(request.mediaFileId()))
        .hasValueSatisfying(
            measurements -> {
              assertThat(measurements.source()).isEqualTo(StreamMeasurementSource.BITRATE_SCAN);
              assertThat(measurements.streams())
                  .containsExactly(
                      expectedStream(VIDEO, VIDEO_BYTES_PER_INTERVAL),
                      expectedStream(AUDIO, AUDIO_BYTES_PER_INTERVAL));
            });
    assertThat(storedChunks(request)).as("The published measurement replaces its chunks").isZero();
    assertThat(scheduledScanPriorities()).isEmpty();
    assertThat(
            meterRegistry.get(FINISHED_SCANS_METRIC).tag("outcome", "measured").counter().count())
        .isOne();
  }

  @Test
  @DisplayName("Should run only the missing chunk when the scan scheduler restarts between chunks")
  void shouldRunOnlyTheMissingChunkWhenTheScanSchedulerRestartsBetweenChunks() throws Exception {
    var request = probedCopyableFile();
    startScanScheduler(
        chunk -> {
          if (!chunk.firstChunk()) {
            throw new ProbeWorkersBusyException("All compatible workers are busy");
          }

          return measured(chunk);
        },
        Clock.systemUTC());
    awaitChunkCompletions(2);
    scanScheduler.stop();
    assertThat(storedChunks(request)).isOne();

    var chunks = new CopyOnWriteArrayList<BitrateScanChunkRequest>();
    retryPendingScansNow();
    startScanScheduler(
        chunk -> {
          chunks.add(chunk);
          return measured(chunk);
        },
        Clock.systemUTC());
    awaitChunkCompletions(1);

    assertThat(chunks)
        .singleElement()
        .satisfies(
            chunk -> {
              assertThat(chunk.keyframes().getFirst()).isEqualTo(keyframe(4_096));
              assertThat(chunk.firstChunk()).isFalse();
            });
    assertThat(outcomes.findStreamMeasurements(request.mediaFileId())).isPresent();
  }

  @Test
  @DisplayName(
      "Should delete the stored chunks and end the scan when the source changes during a chunk")
  void shouldDeleteTheStoredChunksAndEndTheScanWhenTheSourceChangesDuringAChunk() throws Exception {
    var request = probedCopyableFile();
    startScanScheduler(
        chunk -> {
          if (!chunk.firstChunk()) {
            changeSource(request);
          }

          return measured(chunk);
        },
        Clock.systemUTC());
    awaitChunkCompletions(2);

    assertThat(storedChunks(request)).isZero();
    assertThat(outcomes.findStreamMeasurements(request.mediaFileId())).isEmpty();
    assertThat(scheduledScanPriorities())
        .as("The changed source gets a new probe, which asks for its own scan")
        .isEmpty();
  }

  @Test
  @DisplayName("Should scan the new keyframe positions when a probe stores them during a chunk")
  void shouldScanTheNewKeyframePositionsWhenAProbeStoresThemDuringAChunk() throws Exception {
    var request = probedCopyableFile();
    var chunks = new CopyOnWriteArrayList<BitrateScanChunkRequest>();
    startScanScheduler(
        chunk -> {
          if (chunks.isEmpty()) {
            storeOutcome(request, copyableOutcome(OptionalLong.empty(), KEYFRAME_COUNT - 1));
          }

          chunks.add(chunk);
          return measured(chunk);
        },
        Clock.systemUTC());
    awaitChunkCompletions(3);

    assertThat(chunks)
        .extracting(chunk -> chunk.keyframes().size())
        .containsExactly(4_096, 4_096, 3);
    assertThat(outcomes.findStreamMeasurements(request.mediaFileId()))
        .hasValueSatisfying(
            measurements ->
                assertThat(
                        measurements.streams().getFirst().keyframeIntervalBytes().intervalCount())
                    .isEqualTo(KEYFRAME_COUNT - 1));
    assertThat(scheduledScanPriorities()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should delete the stored chunks and end the scan when the stored outcome stops needing it"
          + " during a chunk")
  void shouldDeleteTheStoredChunksAndEndTheScanWhenTheOutcomeStopsNeedingItDuringAChunk()
      throws Exception {
    var request = probedCopyableFile();
    startScanScheduler(
        chunk -> {
          storeOutcome(
              request, withVideoCodec(copyableOutcome(OptionalLong.empty()), "mpeg2video"));
          return measured(chunk);
        },
        Clock.systemUTC());
    awaitChunkCompletions(1);

    assertThat(storedChunks(request)).isZero();
    assertThat(scheduledScanPriorities()).isEmpty();
    assertThat(outcomes.findStreamMeasurements(request.mediaFileId())).isEmpty();
  }

  @Test
  @DisplayName(
      "Should count a failed attempt and start over when the stored chunks measure different"
          + " streams")
  void shouldCountAFailedAttemptAndStartOverWhenTheStoredChunksMeasureDifferentStreams()
      throws Exception {
    var request = probedCopyableFile();
    startScanScheduler(
        chunk -> {
          if (chunk.firstChunk()) {
            return measured(chunk);
          }

          var streams = ((BitrateScanChunkOutcome.Measured) measured(chunk)).streams();
          return new BitrateScanChunkOutcome.Measured(streams.subList(0, 1));
        },
        Clock.systemUTC());
    awaitChunkCompletions(2);

    assertThat(storedChunks(request)).isZero();
    assertThat(pendingScan().failedAttempts()).isOne();
    assertThat(outcomes.findStreamMeasurements(request.mediaFileId())).isEmpty();
    assertThat(meterRegistry.get(CHUNK_FAILURES_METRIC).tag("reason", "assembly").counter().count())
        .isOne();
  }

  @Test
  @DisplayName(
      "Should keep the scan pending for the retry delay without a failure when every worker is"
          + " busy")
  void shouldKeepTheScanPendingForTheRetryDelayWhenEveryWorkerIsBusy() throws Exception {
    var request = probedCopyableFile();
    var clock =
        new MutableClock(
            new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)));

    startScanScheduler(
        _ -> {
          throw new ProbeWorkersBusyException("All compatible workers are busy");
        },
        clock);

    var deferred = chunkCompletions.poll(30, TimeUnit.SECONDS);
    assertThat(deferred).isNotNull();
    assertThat(deferred.getResult()).isEqualTo(ExecutionComplete.Result.OK);
    assertThat(pendingScan().executionTime())
        .isEqualTo(deferred.getTimeDone().plus(probeScheduling.busyWorkerRetryDelay()));
    assertThat(pendingScan().failedAttempts()).isZero();
    assertThat(storedChunks(request)).isZero();
  }

  @Test
  @DisplayName(
      "Should retry the chunk without counting an attempt when the chunk fails transiently")
  void shouldRetryTheChunkWithoutCountingAnAttemptWhenTheChunkFailsTransiently() throws Exception {
    var request = probedCopyableFile();
    var calls = new CopyOnWriteArrayList<BitrateScanChunkRequest>();
    startScanScheduler(
        chunk -> {
          calls.add(chunk);
          if (calls.size() == 1) {
            throw new ProbeExecutionException(
                ItemFailureReason.SOURCE_INACCESSIBLE, "The worker disconnected");
          }

          return measured(chunk);
        },
        Clock.systemUTC());

    var failed = chunkCompletions.poll(30, TimeUnit.SECONDS);
    assertThat(failed).isNotNull();
    assertThat(failed.getResult()).isEqualTo(ExecutionComplete.Result.FAILED);
    assertThat(pendingScan().failedAttempts()).isZero();
    retryPendingScansNow();
    awaitChunkCompletions(2);

    assertThat(outcomes.findStreamMeasurements(request.mediaFileId())).isPresent();
    assertThat(meterRegistry.find(CHUNK_FAILURES_METRIC).counters()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("chunkFailures")
  @DisplayName(
      "Should mark the measurement unavailable and request no further scan when a chunk fails"
          + " three times")
  void shouldMarkTheMeasurementUnavailableAndRequestNoFurtherScanWhenAChunkFailsThreeTimes(
      String reason, Supplier<BitrateScanner> failing) throws Exception {
    var request = probedCopyableFile();
    startScanScheduler(failing.get(), Clock.systemUTC());

    for (var attempt = 1; attempt <= 3; attempt++) {
      assertThat(chunkCompletions.poll(30, TimeUnit.SECONDS)).isNotNull();
      retryPendingScansNow();
    }

    assertThat(storedMeasurementStatus(request)).isEqualTo("UNAVAILABLE FAILED_ATTEMPTS");
    assertThat(scheduledScanPriorities()).isEmpty();
    assertThat(meterRegistry.get(CHUNK_FAILURES_METRIC).tag("reason", reason).counter().count())
        .isEqualTo(3);
    assertThat(
            meterRegistry
                .get(FINISHED_SCANS_METRIC)
                .tag("outcome", "failed_attempts")
                .counter()
                .count())
        .isOne();

    bitrateScanRequester.requestIfNeeded(request.mediaFileId(), request.filepathUri());

    assertThat(scheduledScanPriorities())
        .as("An unavailable measurement of the same inputs needs no scan")
        .isEmpty();
  }

  private static Stream<Arguments> chunkFailures() {
    return Stream.of(
        arguments(
            "timed_out",
            (Supplier<BitrateScanner>)
                () ->
                    _ -> {
                      throw new ProbeTimedOutException(new TimeoutException());
                    }),
        arguments(
            "contract_violation",
            (Supplier<BitrateScanner>)
                () ->
                    _ -> {
                      throw new ProbeContractViolationException(
                          "Worker bitrate scan chunk lists a stream twice");
                    }),
        arguments(
            "media_error",
            (Supplier<BitrateScanner>)
                () -> _ -> new BitrateScanChunkOutcome.MediaError(ProbeError.INVALID_MEDIA)));
  }

  @Test
  @DisplayName(
      "Should mark the measurement unavailable after one chunk when a copy changes a stream's"
          + " packets")
  void shouldMarkTheMeasurementUnavailableAfterOneChunkWhenACopyChangesAStreamsPackets()
      throws Exception {
    var request = probedCopyableFile();
    startScanScheduler(
        _ -> new BitrateScanChunkOutcome.CopyChangesPackets(VIDEO), Clock.systemUTC());

    awaitChunkCompletions(1);

    assertThat(storedMeasurementStatus(request)).isEqualTo("UNAVAILABLE COPY_CHANGES_PACKETS");
    assertThat(scheduledScanPriorities()).isEmpty();
    assertThat(
            meterRegistry
                .get(FINISHED_SCANS_METRIC)
                .tag("outcome", "copy_changes_packets")
                .counter()
                .count())
        .isOne();
  }

  @Test
  @DisplayName(
      "Should run a waiting keyframe packet scan before the next chunk when both wait in the scan"
          + " table")
  void shouldRunAWaitingKeyframePacketScanBeforeTheNextChunkWhenBothWait() throws Exception {
    var files = requestUnchangedFiles(2);
    probe(copyableOutcome(OptionalLong.empty()))
        .execute(files.getFirst(), ProbePriority.BACKGROUND);
    probe(packetScanRequired()).execute(files.getLast(), ProbePriority.BACKGROUND);
    var order = new CopyOnWriteArrayList<String>();
    var execution =
        KeyframePacketScanExecution.builder()
            .reader(reader)
            .scanner(
                _ -> {
                  order.add("keyframe packet scan");
                  return copyableOutcome(OptionalLong.empty());
                })
            .fileSystem(FileSystems.getDefault())
            .outcomes(outcomes)
            .meterRegistry(meterRegistry)
            .build();

    startScanScheduler(
        execution,
        chunk -> {
          order.add("bitrate scan chunk");
          return measured(chunk);
        },
        Clock.systemUTC());
    awaitChunkCompletions(2);

    assertThat(order.getFirst()).isEqualTo("keyframe packet scan");
  }

  private ProbeTaskRequest probedCopyableFile() throws IOException {
    var request = requestUnchangedFiles(1).getFirst();
    probe(copyableOutcome(OptionalLong.empty())).execute(request, ProbePriority.BACKGROUND);
    return request;
  }

  private ProbeExecution probe(ProbeOutcome outcome) {
    var producer = new FakeFfprobeService();
    producer.answerWith(outcome);
    return probeExecution.toBuilder().producer(producer).build();
  }

  private void startScanScheduler(BitrateScanner scanner, Clock clock) {
    startScanScheduler(keyframePacketScanExecution, scanner, clock);
  }

  private void startScanScheduler(
      KeyframePacketScanExecution keyframeScans, BitrateScanner scanner, Clock clock) {
    var execution =
        BitrateScanExecution.builder()
            .scans(bitrateScans)
            .scanner(scanner)
            .fileSystem(FileSystems.getDefault())
            .meterRegistry(meterRegistry)
            .build();
    scanScheduler =
        scanSchedulerBuilder()
            .execution(keyframeScans)
            .bitrateScanExecution(execution)
            .meterRegistry(meterRegistry)
            .clock(clock)
            .listeners(List.of(recordingChunkCompletions()))
            .build();
    scanScheduler.start();
  }

  private AbstractSchedulerListener recordingChunkCompletions() {
    return new AbstractSchedulerListener() {
      @Override
      public void onExecutionComplete(ExecutionComplete executionComplete) {
        if (executionComplete
            .getExecution()
            .taskInstance
            .getTaskName()
            .equals(BitrateScanTask.NAME)) {
          chunkCompletions.add(executionComplete);
        }
      }
    };
  }

  private void awaitChunkCompletions(int count) throws InterruptedException {
    for (var completed = 0; completed < count; completed++) {
      assertThat(chunkCompletions.poll(30, TimeUnit.SECONDS)).isNotNull();
    }
  }

  // A worker that measures every interval of each requested stream with one packet.
  private static BitrateScanChunkOutcome measured(BitrateScanChunkRequest chunk) {
    var intervals = chunk.keyframes().size();
    var first = chunk.keyframes().getFirst().presentationTime();
    var end =
        chunk
            .end()
            .map(KeyframePosition::presentationTime)
            .orElse(chunk.keyframes().getLast().presentationTime() + TICKS_PER_KEYFRAME);
    return new BitrateScanChunkOutcome.Measured(
        chunk.measuredStreamIndexes().stream()
            .map(
                index ->
                    MeasuredChunkStream.builder()
                        .streamIndex(index)
                        .measuredBytes(bytesPerInterval(index) * intervals)
                        .packetCount(intervals)
                        .timeBaseNumerator(1)
                        .timeBaseDenominator(90_000)
                        .packetTimes(Optional.of(new MeasuredChunkStream.PacketTimes(first, end)))
                        .keyframeIntervalBytes(repeated(bytesPerInterval(index), intervals))
                        .keyframeIntervalPacketCounts(repeated(1, intervals))
                        .build())
            .toList());
  }

  private static MeasuredStream expectedStream(int index, long bytesPerInterval) {
    return MeasuredStream.builder()
        .streamIndex(index)
        .measuredBytes(bytesPerInterval * KEYFRAME_COUNT)
        .packetCount(KEYFRAME_COUNT)
        .timeBaseNumerator(1)
        .timeBaseDenominator(90_000)
        .measuredDuration(TICKS_PER_KEYFRAME * KEYFRAME_COUNT)
        .keyframeIntervalBytes(repeated(bytesPerInterval, KEYFRAME_COUNT))
        .keyframeIntervalPacketCounts(repeated(1, KEYFRAME_COUNT))
        .build();
  }

  private static long bytesPerInterval(int streamIndex) {
    if (streamIndex == VIDEO) {
      return VIDEO_BYTES_PER_INTERVAL;
    }

    return AUDIO_BYTES_PER_INTERVAL;
  }

  private static KeyframeIntervalCounts repeated(long count, int intervals) {
    var counts = new long[intervals];
    Arrays.fill(counts, count);
    return KeyframeIntervalCounts.of(counts);
  }

  private static KeyframePosition keyframe(int index) {
    return new KeyframePosition(index * TICKS_PER_KEYFRAME, index * TICKS_PER_KEYFRAME);
  }

  private static ProbeOutcome.Success copyableOutcome(OptionalLong videoBitrate) {
    return copyableOutcome(videoBitrate, KEYFRAME_COUNT);
  }

  private static ProbeOutcome.Success copyableOutcome(OptionalLong videoBitrate, int keyframes) {
    return new ProbeOutcome.Success(
        ProbeContainer.builder()
            .format(Optional.of("matroska,webm"))
            .duration(Optional.of(Duration.ofSeconds(KEYFRAME_COUNT)))
            .build(),
        List.of(video(videoBitrate), audio()),
        new KeyframeKnowledge.Known(
            KeyframePositions.builder()
                .streamIndex(VIDEO)
                .timeBaseNumerator(1)
                .timeBaseDenominator(90_000)
                .source(KeyframePositionSource.CONTAINER_INDEX)
                .keyframes(
                    IntStream.range(0, keyframes).mapToObj(index -> keyframe(index)).toList())
                .build()));
  }

  private static ProbeOutcome.Success withVideoCodec(ProbeOutcome.Success outcome, String codec) {
    return new ProbeOutcome.Success(
        outcome.container(),
        List.of(
            StreamInfo.builder().index(VIDEO).codecType("video").codec(Optional.of(codec)).build(),
            audio()),
        outcome.keyframes());
  }

  // Stores the outcome as a probe of the request's inputs would, without requesting a scan.
  private void storeOutcome(ProbeTaskRequest request, ProbeOutcome outcome) {
    outcomes.publish(
        ProbePublication.builder()
            .mediaFileId(request.mediaFileId())
            .snapshot(request.snapshot())
            .probeVersion(request.probeVersion())
            .outcome(outcome)
            .build());
  }

  private static ProbeOutcome.Success packetScanRequired() {
    var copyable = copyableOutcome(OptionalLong.empty());
    return new ProbeOutcome.Success(
        copyable.container(),
        copyable.streams(),
        new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX));
  }

  private static StreamInfo video(OptionalLong bitrate) {
    return StreamInfo.builder()
        .index(VIDEO)
        .codecType("video")
        .codec(Optional.of("h264"))
        .bitrate(bitrate)
        .build();
  }

  private static StreamInfo audio() {
    return StreamInfo.builder().index(AUDIO).codecType("audio").codec(Optional.of("aac")).build();
  }

  private List<Integer> scheduledScanPriorities() {
    return dsl.select(DSL.field("priority", Integer.class))
        .from(DSL.table(KeyframePacketScanScheduler.TABLE_NAME))
        .where(DSL.field("task_name", String.class).eq(BitrateScanTask.NAME))
        .fetch(DSL.field("priority", Integer.class));
  }

  private int storedChunks(ProbeTaskRequest request) {
    return dsl.fetchCount(
        MEDIA_FILE_BITRATE_SCAN_CHUNK,
        MEDIA_FILE_BITRATE_SCAN_CHUNK.MEDIA_FILE_ID.eq(request.mediaFileId()));
  }

  private String storedMeasurementStatus(ProbeTaskRequest request) {
    var measurement = MEDIA_FILE_STREAM_MEASUREMENT;
    return dsl.select(measurement.STATUS, measurement.UNAVAILABLE_REASON)
        .from(measurement)
        .where(measurement.MEDIA_FILE_ID.eq(request.mediaFileId()))
        .fetchSingle(row -> row.value1() + " " + row.value2());
  }

  private record PendingScan(Instant executionTime, int failedAttempts) {}

  private PendingScan pendingScan() {
    var row =
        dsl.select(DSL.field("execution_time", Instant.class), DSL.field("task_data", byte[].class))
            .from(DSL.table(KeyframePacketScanScheduler.TABLE_NAME))
            .where(DSL.field("task_name", String.class).eq(BitrateScanTask.NAME))
            .and(DSL.field("picked", Boolean.class).isFalse())
            .fetchSingle();
    return new PendingScan(
        row.value1(),
        taskDataSerializer
            .deserialize(BitrateScanTaskRequest.class, row.value2())
            .failedAttempts());
  }

  // Stands in for the retry delay elapsing.
  private void retryPendingScansNow() {
    dsl.update(DSL.table(KeyframePacketScanScheduler.TABLE_NAME))
        .set(DSL.field("execution_time", Instant.class), Instant.now())
        .where(DSL.field("picked", Boolean.class).isFalse())
        .execute();
  }

  private static void changeSource(ProbeTaskRequest request) {
    try {
      Files.write(
          FilepathCodec.decode(request.filepathUri()), new byte[] {9}, StandardOpenOption.APPEND);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
