package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.BitrateScanChunkFixture.chunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.BitrateScanChunkOutcome;
import com.streamarr.server.domain.streaming.BitrateScanChunkRequest;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.task.BitrateScanInputs;
import com.streamarr.server.domain.task.BitrateScanKey;
import com.streamarr.server.domain.task.BitrateScanTaskRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fakes.FakeBitrateScanRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.streaming.BitrateScanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Bitrate scan execution")
class BitrateScanExecutionTest {

  private static final UUID MEDIA_FILE_ID = UUID.randomUUID();
  private static final KeyframePositions POSITIONS =
      KeyframePositions.builder()
          .streamIndex(0)
          .timeBaseNumerator(1)
          .timeBaseDenominator(90_000)
          .source(KeyframePositionSource.CONTAINER_INDEX)
          .keyframes(List.of(new KeyframePosition(0, 0), new KeyframePosition(90_000, 90_000)))
          .build();

  @TempDir Path tempDir;

  private final FakeBitrateScanRepository scans = new FakeBitrateScanRepository();
  private final List<BitrateScanChunkRequest> chunks = new CopyOnWriteArrayList<>();
  private Path source;
  private BitrateScanTaskRequest request;

  @BeforeEach
  void storeOutcomeNeedingScan() throws IOException {
    source = Files.write(tempDir.resolve("movie.mkv"), new byte[] {1, 2, 3});
    var key = new BitrateScanKey(snapshotOf(source), "aa", StreamMeasurements.CURRENT_VERSION);
    scans.storeOutcomeNeedingScan(MEDIA_FILE_ID, inputs(key));
    request =
        BitrateScanTaskRequest.builder()
            .mediaFileId(MEDIA_FILE_ID)
            .filepathUri(FilepathCodec.encode(source))
            .snapshot(key.snapshot())
            .keyframePositionsDigest(key.keyframePositionsDigest())
            .measurementVersion(key.measurementVersion())
            .build();
  }

  @Test
  @DisplayName(
      "Should complete without a chunk when the stored outcome needs a scan of other inputs")
  void shouldCompleteWithoutAChunkWhenTheStoredOutcomeNeedsAScanOfOtherInputs() {
    var result =
        execution(this::measured)
            .execute(request.toBuilder().keyframePositionsDigest("bb").build());

    assertThat(result).isEqualTo(new BitrateScanResult.Completed());
    assertThat(chunks).isEmpty();
  }

  @Test
  @DisplayName("Should report a changed source without a chunk when the source changed")
  void shouldReportAChangedSourceWithoutAChunkWhenTheSourceChanged() throws IOException {
    Files.write(source, new byte[] {4}, StandardOpenOption.APPEND);

    assertThat(execution(this::measured).execute(request))
        .isEqualTo(new BitrateScanResult.SourceChanged());
    assertThat(chunks).isEmpty();
  }

  @Test
  @DisplayName("Should report every chunk stored without a chunk when the chunks cover the file")
  void shouldReportEveryChunkStoredWithoutAChunkWhenTheChunksCoverTheFile() {
    scans.storeChunkFollowing(MEDIA_FILE_ID, request.key(), chunk(0, 2));

    assertThat(execution(this::measured).execute(request))
        .isEqualTo(new BitrateScanResult.AllChunksStored());
    assertThat(chunks).isEmpty();
  }

  @Test
  @DisplayName(
      "Should run the first chunk again when the stored chunks follow another measurement version")
  void shouldRunTheFirstChunkAgainWhenTheStoredChunksFollowAnotherMeasurementVersion() {
    var otherVersion =
        new BitrateScanKey(
            request.snapshot(),
            request.keyframePositionsDigest(),
            request.measurementVersion() + 1);
    scans.storeChunkFollowing(MEDIA_FILE_ID, otherVersion, chunk(0, 2));

    assertThat(execution(this::measured).execute(request))
        .isEqualTo(new BitrateScanResult.ChunkStored());
    assertThat(chunks)
        .singleElement()
        .extracting(BitrateScanChunkRequest::firstChunk)
        .isEqualTo(true);
    assertThat(scans.chunks(MEDIA_FILE_ID))
        .extracting(FakeBitrateScanRepository.Chunk::key)
        .containsExactly(request.key());
  }

  @Test
  @DisplayName(
      "Should complete without storing the chunk when the stored outcome changes during the chunk")
  void shouldCompleteWithoutStoringTheChunkWhenTheStoredOutcomeChangesDuringTheChunk() {
    BitrateScanner changingOutcome =
        chunk -> {
          scans.storeOutcomeNeedingScan(
              MEDIA_FILE_ID,
              inputs(
                  new BitrateScanKey(
                      request.snapshot(), "bb", StreamMeasurements.CURRENT_VERSION)));
          return measured(chunk);
        };

    assertThat(execution(changingOutcome).execute(request))
        .isEqualTo(new BitrateScanResult.Completed());
    assertThat(scans.chunks(MEDIA_FILE_ID)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should ask for a chunk whose keyframes, each measured in every measured stream, fit the"
          + " scanner's measured keyframe interval limit when the file has several measured streams")
  void shouldAskForChunkThatFitsScannersLimitWhenFileHasSeveralMeasuredStreams() {
    scans.storeOutcomeNeedingScan(
        MEDIA_FILE_ID,
        inputsBuilder(request.key())
            .positions(positionsOf(5))
            .audioStreamIndexes(List.of(1, 2))
            .build());

    var result = execution(measuringWithin(7)).execute(request);

    // Three measured streams: two keyframes make six intervals, and a third would make nine.
    assertThat(result).isEqualTo(new BitrateScanResult.ChunkStored());
    assertThat(chunks)
        .singleElement()
        .satisfies(
            chunk -> {
              assertThat(chunk.measuredStreamIndexes()).containsExactly(0, 1, 2);
              assertThat(chunk.keyframes()).isEqualTo(positionsOf(5).keyframes().subList(0, 2));
              assertThat(chunk.end()).contains(positionsOf(5).keyframes().get(2));
            });
  }

  @Test
  @DisplayName("Should fail the execution for a retry when the source cannot be read")
  void shouldFailTheExecutionForARetryWhenTheSourceCannotBeRead() throws IOException {
    Files.delete(source);
    Files.createSymbolicLink(source, source.getFileName());

    assertThatThrownBy(() -> execution(this::measured).execute(request))
        .isInstanceOfSatisfying(
            ProbeExecutionException.class,
            failure ->
                assertThat(failure.reason()).isEqualTo(ItemFailureReason.SOURCE_INACCESSIBLE));
    assertThat(chunks).isEmpty();
  }

  private BitrateScanExecution execution(BitrateScanner scanner) {
    return BitrateScanExecution.builder()
        .scans(scans)
        .scanner(
            new BitrateScanner() {
              @Override
              public BitrateScanChunkOutcome scanChunk(BitrateScanChunkRequest chunk) {
                chunks.add(chunk);
                return scanner.scanChunk(chunk);
              }

              @Override
              public int measuredKeyframeIntervalLimit() {
                return scanner.measuredKeyframeIntervalLimit();
              }
            })
        .fileSystem(FileSystems.getDefault())
        .meterRegistry(new SimpleMeterRegistry())
        .build();
  }

  private BitrateScanner measuringWithin(int measuredKeyframeIntervalLimit) {
    return new BitrateScanner() {
      @Override
      public BitrateScanChunkOutcome scanChunk(BitrateScanChunkRequest chunk) {
        return measured(chunk);
      }

      @Override
      public int measuredKeyframeIntervalLimit() {
        return measuredKeyframeIntervalLimit;
      }
    };
  }

  private BitrateScanChunkOutcome measured(BitrateScanChunkRequest chunk) {
    return new BitrateScanChunkOutcome.Measured(chunk(0, chunk.keyframes().size()).streams());
  }

  private static BitrateScanInputs inputs(BitrateScanKey key) {
    return inputsBuilder(key).build();
  }

  private static BitrateScanInputs.BitrateScanInputsBuilder inputsBuilder(BitrateScanKey key) {
    return BitrateScanInputs.builder()
        .key(key)
        .positions(POSITIONS)
        .containerDuration(Optional.empty())
        .audioStreamIndexes(List.of())
        .chosenVideoHasProbedBitrate(false);
  }

  /** One keyframe each second of the video stream. */
  private static KeyframePositions positionsOf(int keyframes) {
    return KeyframePositions.builder()
        .streamIndex(POSITIONS.streamIndex())
        .timeBaseNumerator(POSITIONS.timeBaseNumerator())
        .timeBaseDenominator(POSITIONS.timeBaseDenominator())
        .source(POSITIONS.source())
        .keyframes(
            IntStream.range(0, keyframes)
                .mapToObj(index -> new KeyframePosition(index * 90_000L, index * 90_000L))
                .toList())
        .build();
  }

  private static SourceFileSnapshot snapshotOf(Path path) throws IOException {
    var attributes = Files.readAttributes(path, BasicFileAttributes.class);
    return new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant());
  }
}
