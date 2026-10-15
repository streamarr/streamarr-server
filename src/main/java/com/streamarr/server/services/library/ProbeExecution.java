package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.task.KeyframePacketScanTaskRequest;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.streaming.FfprobeService;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One probe execution, independent of the scheduler that runs it. Failed attempts escape as {@link
 * ProbeExecutionException} so the scheduler records their reason and retries them with backoff;
 * cancelled attempts retry without a recorded failure, and busy workers defer the probe without a
 * failure.
 */
@Service
@Builder(toBuilder = true)
@RequiredArgsConstructor
@Slf4j
public class ProbeExecution {

  private final MediaFileRepository mediaFiles;
  private final PersistedProbeReader reader;
  private final FfprobeService producer;
  private final FileSystem fileSystem;
  private final MediaFileContainerInfoRepository outcomes;
  private final KeyframePacketScanRequests packetScans;
  private final PlatformTransactionManager transactionManager;

  public ProbeExecutionResult execute(ProbeTaskRequest request) {
    if (request.probeVersion() > ProbeVersion.CURRENT) {
      log.warn(
          "Removing probe request for media file {} at unsupported version {}",
          request.mediaFileId(),
          request.probeVersion());
      return new ProbeExecutionResult.Completed();
    }

    if (!mediaFiles.existsById(request.mediaFileId())) {
      return new ProbeExecutionResult.Completed();
    }

    var path = FilepathCodec.decode(fileSystem, request.filepathUri());
    var before = snapshot(path);
    if (before.isEmpty()) {
      return new ProbeExecutionResult.SourceRemoved();
    }

    var observed = before.get();
    if (!observed.equals(request.snapshot())) {
      return new ProbeExecutionResult.SourceChanged(
          request.toBuilder().snapshot(observed).probeVersion(ProbeVersion.CURRENT).build());
    }

    if (request.probeVersion() < ProbeVersion.CURRENT) {
      return new ProbeExecutionResult.Rescheduled(
          request.toBuilder().probeVersion(ProbeVersion.CURRENT).build());
    }

    if (reader
        .find(request.mediaFileId())
        .filter(stored -> stored.matches(observed, request.probeVersion()))
        .isPresent()) {
      return new ProbeExecutionResult.Completed();
    }

    ProbeOutcome outcome;
    try {
      outcome =
          producer.probe(
              ProbeExecutionRequest.builder()
                  .sourcePath(path)
                  .attemptId(UUID.randomUUID())
                  .probeVersion(request.probeVersion())
                  .build());
    } catch (ProbeWorkersBusyException _) {
      log.debug(
          "Deferring probe for media file {}: all compatible workers are busy",
          request.mediaFileId());
      return new ProbeExecutionResult.Deferred(request);
    }

    var after = snapshot(path);
    if (after.isEmpty()) {
      return new ProbeExecutionResult.SourceRemoved();
    }

    if (!after.get().equals(observed)) {
      return new ProbeExecutionResult.SourceChanged(
          request.toBuilder().snapshot(after.get()).build());
    }

    publish(request, observed, outcome);
    return new ProbeExecutionResult.Completed();
  }

  // The scan is scheduled in the publication's transaction, so a stored outcome that needs a scan
  // always has one.
  private void publish(
      ProbeTaskRequest request, SourceFileSnapshot observed, ProbeOutcome outcome) {
    var publication =
        ProbePublication.builder()
            .mediaFileId(request.mediaFileId())
            .snapshot(observed)
            .probeVersion(request.probeVersion())
            .outcome(outcome)
            .build();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              if (outcomes.publish(publication) && outcome.needsKeyframePacketScan()) {
                packetScans.request(
                    KeyframePacketScanTaskRequest.builder()
                        .mediaFileId(request.mediaFileId())
                        .filepathUri(request.filepathUri())
                        .snapshot(observed)
                        .probeVersion(request.probeVersion())
                        .build());
              }
            });
  }

  private static Optional<SourceFileSnapshot> snapshot(Path path) {
    try {
      return SourceFileSnapshots.read(path);
    } catch (IOException exception) {
      throw new ProbeExecutionException(
          ItemFailureReason.SOURCE_INACCESSIBLE,
          "The server could not read the media source",
          exception);
    }
  }
}
