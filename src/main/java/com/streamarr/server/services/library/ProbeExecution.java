package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.streaming.FfprobeService;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * One probe execution, independent of the scheduler that runs it. Transient failures escape as
 * {@link ProbeExecutionException} so the scheduler retries them with backoff.
 */
@Service
@Builder(toBuilder = true)
@RequiredArgsConstructor
@Slf4j
public class ProbeExecution {

  private final MediaFileRepository mediaFiles;
  private final PersistedProbeReader reader;
  private final FfprobeService producer;
  private final FileStabilityChecker stabilityChecker;
  private final FileSystem fileSystem;
  private final MediaFileContainerInfoRepository outcomes;

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
    if (snapshot(path).isEmpty()) {
      return new ProbeExecutionResult.Completed();
    }

    if (!stabilityChecker.waitForStability(path)) {
      throw new ProbeExecutionException("Source did not stabilize");
    }

    var before = snapshot(path);
    if (before.isEmpty()) {
      return new ProbeExecutionResult.Completed();
    }

    var observed = before.get();
    if (!observed.equals(request.snapshot()) || request.probeVersion() < ProbeVersion.CURRENT) {
      return new ProbeExecutionResult.Rescheduled(
          request.toBuilder().snapshot(observed).probeVersion(ProbeVersion.CURRENT).build());
    }

    if (reader
        .find(request.mediaFileId())
        .filter(stored -> stored.matches(observed, request.probeVersion()))
        .isPresent()) {
      return new ProbeExecutionResult.Completed();
    }

    var outcome = producer.probe(path);
    var after = snapshot(path);
    if (after.isEmpty()) {
      return new ProbeExecutionResult.Completed();
    }

    if (!after.get().equals(observed)) {
      return new ProbeExecutionResult.Rescheduled(
          request.toBuilder().snapshot(after.get()).build());
    }

    outcomes.publish(
        ProbePublication.builder()
            .mediaFileId(request.mediaFileId())
            .snapshot(observed)
            .probeVersion(request.probeVersion())
            .outcome(outcome)
            .build());
    return new ProbeExecutionResult.Completed();
  }

  private static Optional<SourceFileSnapshot> snapshot(Path path) {
    try {
      var attributes = Files.readAttributes(path, BasicFileAttributes.class);
      return Optional.of(
          new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant()));
    } catch (NoSuchFileException _) {
      return Optional.empty();
    } catch (IOException exception) {
      throw new ProbeExecutionException(exception);
    }
  }
}
