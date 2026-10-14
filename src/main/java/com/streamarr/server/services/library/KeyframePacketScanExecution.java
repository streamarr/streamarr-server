package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.task.KeyframePacketScanPublication;
import com.streamarr.server.domain.task.KeyframePacketScanTaskRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.ProbeTimedOutException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.streaming.KeyframePacketScanner;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * One keyframe packet scan execution, independent of the scheduler that runs it. Transient failures
 * escape as {@link ProbeExecutionException} so the scheduler retries them with backoff. Busy
 * workers defer the scan without a failure. A scan that times out or reports no keyframe result
 * ends as {@link KeyframePacketScanResult.Failed}, which its completion counts.
 */
@Service
@Builder
@RequiredArgsConstructor
@Slf4j
public class KeyframePacketScanExecution {

  private final PersistedProbeReader reader;
  private final KeyframePacketScanner scanner;
  private final FileSystem fileSystem;
  private final MediaFileContainerInfoRepository outcomes;

  public KeyframePacketScanResult execute(KeyframePacketScanTaskRequest request) {
    if (request.probeVersion() != ProbeVersion.CURRENT || !storedOutcomeNeedsScan(request)) {
      return new KeyframePacketScanResult.Completed();
    }

    var path = FilepathCodec.decode(fileSystem, request.filepathUri());
    if (!sourceMatches(path, request.snapshot())) {
      return new KeyframePacketScanResult.Completed();
    }

    ProbeOutcome outcome;
    try {
      outcome =
          scanner.scanKeyframePackets(
              ProbeExecutionRequest.builder()
                  .sourcePath(path)
                  .attemptId(UUID.randomUUID())
                  .probeVersion(request.probeVersion())
                  .build());
    } catch (ProbeWorkersBusyException _) {
      log.debug(
          "Deferring keyframe packet scan for media file {}: all compatible workers are busy",
          request.mediaFileId());
      return new KeyframePacketScanResult.Deferred();
    } catch (ProbeTimedOutException _) {
      log.warn("Keyframe packet scan for media file {} timed out", request.mediaFileId());
      return new KeyframePacketScanResult.Failed();
    }

    // A source that changed during the scan gets a new probe when the server observes the change.
    if (!sourceMatches(path, request.snapshot())) {
      return new KeyframePacketScanResult.Completed();
    }

    return publish(request, outcome);
  }

  private KeyframePacketScanResult publish(
      KeyframePacketScanTaskRequest request, ProbeOutcome outcome) {
    var keyframes = scannedKeyframes(outcome);
    if (keyframes.isEmpty()) {
      log.warn(
          "Keyframe packet scan for media file {} reported no keyframe result",
          request.mediaFileId());
      return new KeyframePacketScanResult.Failed();
    }

    outcomes.publishKeyframePacketScan(
        KeyframePacketScanPublication.builder()
            .mediaFileId(request.mediaFileId())
            .snapshot(request.snapshot())
            .probeVersion(request.probeVersion())
            .keyframes(keyframes.get())
            .build());
    return new KeyframePacketScanResult.Completed();
  }

  private boolean storedOutcomeNeedsScan(KeyframePacketScanTaskRequest request) {
    return reader
        .find(request.mediaFileId())
        .filter(stored -> stored.matches(request.snapshot(), request.probeVersion()))
        .filter(stored -> stored.outcome().needsKeyframePacketScan())
        .isPresent();
  }

  private static Optional<KeyframeKnowledge> scannedKeyframes(ProbeOutcome outcome) {
    return switch (outcome) {
      case ProbeOutcome.Failure _ ->
          Optional.of(
              new KeyframeKnowledge.Unavailable(
                  KeyframesUnavailableReason.PACKET_SCAN_MEDIA_ERROR));
      case ProbeOutcome.Success(_, _, var keyframes) -> reportedKeyframes(keyframes);
    };
  }

  private static Optional<KeyframeKnowledge> reportedKeyframes(KeyframeKnowledge keyframes) {
    return switch (keyframes) {
      case KeyframeKnowledge.Known _, KeyframeKnowledge.Unavailable _ -> Optional.of(keyframes);
      case KeyframeKnowledge.PacketScanRequired _, KeyframeKnowledge.NotReported _ ->
          Optional.empty();
    };
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
