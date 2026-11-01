package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.BitrateScanChunkOutcome;
import com.streamarr.server.domain.streaming.BitrateScanChunkRequest;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.task.BitrateScanChunkPublication;
import com.streamarr.server.domain.task.BitrateScanInputs;
import com.streamarr.server.domain.task.BitrateScanTaskRequest;
import com.streamarr.server.domain.task.StoredBitrateScanChunk;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.ProbeTimedOutException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.repositories.media.BitrateScanRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.streaming.BitrateScanner;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Runs the next chunk of a file's bitrate scan (ADR 0054), independent of the scheduler that runs
 * it. Each execution runs at most one chunk: the first keyframe interval that no stored chunk
 * covers starts it. Transient failures, such as a worker that disconnects, escape as {@link
 * ProbeExecutionException}, so the scheduler retries the chunk with backoff on any worker without
 * counting an attempt. Busy workers defer the chunk without a failure.
 */
@Service
@Builder
@RequiredArgsConstructor
@Slf4j
public class BitrateScanExecution {

  static final String CHUNK_FAILURES_METRIC = "streamarr.probes.bitrate_scan_chunk_failures";

  private final BitrateScanRepository scans;
  private final BitrateScanner scanner;
  private final FileSystem fileSystem;
  @NonNull private final MeterRegistry meterRegistry;

  public BitrateScanResult execute(BitrateScanTaskRequest request) {
    var stored = scans.findBitrateScanInputs(request.mediaFileId());
    if (stored.isEmpty() || !stored.get().key().equals(request.key())) {
      return new BitrateScanResult.Completed();
    }

    var inputs = stored.get();
    var path = FilepathCodec.decode(fileSystem, request.filepathUri());
    if (!sourceMatches(path, request.snapshot())) {
      return new BitrateScanResult.SourceChanged();
    }

    scans.deleteChunksNotFollowing(request.mediaFileId(), request.key());
    var covered =
        BitrateScanChunks.coveredIntervals(scans.findChunks(request.mediaFileId(), request.key()));
    if (covered >= inputs.positions().keyframes().size()) {
      return new BitrateScanResult.AllChunksStored();
    }

    var chunk = BitrateScanChunks.next(fileOf(inputs), covered);
    BitrateScanChunkOutcome outcome;
    try {
      outcome = scanner.scanChunk(chunkRequest(path, inputs, chunk));
    } catch (ProbeWorkersBusyException deferral) {
      log.debug(
          "Deferring bitrate scan chunk for media file {}: {}",
          request.mediaFileId(),
          deferral.getMessage());
      return new BitrateScanResult.Deferred();
    } catch (ProbeTimedOutException _) {
      return failed(request, "timed_out");
    } catch (ProbeContractViolationException violation) {
      log.warn(
          "Bitrate scan chunk for media file {} got a worker reply that breaks the worker"
              + " contract: {}",
          request.mediaFileId(),
          violation.getMessage());
      return failed(request, "contract_violation");
    }

    if (!sourceMatches(path, request.snapshot())) {
      return new BitrateScanResult.SourceChanged();
    }

    return switch (outcome) {
      case BitrateScanChunkOutcome.Measured(var streams) ->
          storeChunk(
              request,
              StoredBitrateScanChunk.builder()
                  .firstKeyframeInterval(chunk.firstKeyframeInterval())
                  .keyframeIntervalCount(chunk.keyframeIntervalCount())
                  .streams(streams)
                  .build());
      case BitrateScanChunkOutcome.CopyChangesPackets _ ->
          new BitrateScanResult.CopyChangesPackets();
      case BitrateScanChunkOutcome.MediaError _ -> failed(request, "media_error");
    };
  }

  static BitrateScanChunks.File fileOf(BitrateScanInputs inputs) {
    return BitrateScanChunks.File.builder()
        .positions(inputs.positions())
        .size(inputs.key().snapshot().size())
        .containerDuration(inputs.containerDuration())
        .build();
  }

  private BitrateScanResult storeChunk(
      BitrateScanTaskRequest request, StoredBitrateScanChunk chunk) {
    var stored =
        scans.storeChunk(
            BitrateScanChunkPublication.builder()
                .mediaFileId(request.mediaFileId())
                .key(request.key())
                .chunk(chunk)
                .build());
    if (!stored) {
      return new BitrateScanResult.Completed();
    }

    return new BitrateScanResult.ChunkStored();
  }

  private static BitrateScanChunkRequest chunkRequest(
      Path path, BitrateScanInputs inputs, BitrateScanChunks.PlannedChunk chunk) {
    var positions = inputs.positions();
    var end = chunk.firstKeyframeInterval() + chunk.keyframeIntervalCount();
    Optional<KeyframePosition> next = Optional.empty();
    if (end < positions.keyframes().size()) {
      next = Optional.of(positions.keyframes().get(end));
    }

    return BitrateScanChunkRequest.builder()
        .sourcePath(path)
        .attemptId(UUID.randomUUID())
        .measurementVersion(inputs.key().measurementVersion())
        .chosenVideoStreamIndex(positions.streamIndex())
        .measuredStreamIndexes(
            Stream.concat(Stream.of(positions.streamIndex()), inputs.audioStreamIndexes().stream())
                .toList())
        .timeBaseNumerator(positions.timeBaseNumerator())
        .timeBaseDenominator(positions.timeBaseDenominator())
        .keyframes(BitrateScanChunks.keyframesOf(positions, chunk))
        .end(next)
        .firstChunk(chunk.firstKeyframeInterval() == 0)
        .build();
  }

  private BitrateScanResult failed(BitrateScanTaskRequest request, String reason) {
    log.warn("Bitrate scan chunk for media file {} failed: {}", request.mediaFileId(), reason);
    meterRegistry.counter(CHUNK_FAILURES_METRIC, "reason", reason).increment();
    return new BitrateScanResult.Failed();
  }

  private static boolean sourceMatches(Path path, SourceFileSnapshot expected) {
    try {
      return SourceFileSnapshots.read(path).filter(expected::equals).isPresent();
    } catch (IOException exception) {
      throw new ProbeExecutionException(
          ItemFailureReason.SOURCE_INACCESSIBLE,
          "The server could not read the media source",
          exception);
    }
  }
}
