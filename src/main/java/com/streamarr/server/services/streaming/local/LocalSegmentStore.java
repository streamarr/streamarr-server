package com.streamarr.server.services.streaming.local;

import com.streamarr.server.exceptions.InvalidSegmentPathException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.streaming.SegmentStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LocalSegmentStore implements SegmentStore {

  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
  private static final Duration DEATH_GRACE = Duration.ofSeconds(1);
  private static final Duration SAFETY_CEILING = Duration.ofMinutes(2);

  private final Path baseDir;
  private final ConcurrentHashMap<UUID, Path> sessionDirs = new ConcurrentHashMap<>();

  public LocalSegmentStore(Path baseDir) {
    this.baseDir = baseDir;
  }

  @Override
  public byte[] readSegment(UUID sessionId, String segmentName) {
    var segmentPath = resolveSegmentPath(sessionId, segmentName);

    try {
      return Files.readAllBytes(segmentPath);
    } catch (NoSuchFileException e) {
      throw new TranscodeException("Segment not found: " + segmentName, e);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read segment: " + segmentName, e);
    }
  }

  /**
   * A session without an output directory reads as "segment not yet present" rather than an error:
   * in remote mode nothing creates the directory until a worker's first upload, so the wait must
   * bridge that window exactly as it bridges encoder startup.
   */
  // The wait tracks producer liveness, not a stopwatch: a slow but live producer is waited on
  // indefinitely (up to a safety ceiling), while a dead producer is given a brief grace — enough to
  // cover a seek stop→start blip and a late final segment — then abandoned.
  @Override
  public boolean waitForSegment(UUID sessionId, String segmentName, BooleanSupplier producerAlive) {
    var ceiling = System.nanoTime() + SAFETY_CEILING.toNanos();
    var graceStarted = false;
    var graceDeadline = 0L;

    while (true) {
      if (segmentExists(sessionId, segmentName)) {
        return true;
      }
      if (producerAlive.getAsBoolean()) {
        graceStarted = false;
      } else if (!graceStarted) {
        graceStarted = true;
        graceDeadline = System.nanoTime() + DEATH_GRACE.toNanos();
      } else if (System.nanoTime() - graceDeadline >= 0) {
        return segmentExists(sessionId, segmentName);
      }
      if (System.nanoTime() - ceiling >= 0) {
        return segmentExists(sessionId, segmentName);
      }
      try {
        Thread.sleep(POLL_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  @Override
  public boolean segmentExists(UUID sessionId, String segmentName) {
    try {
      return Files.exists(resolveSegmentPath(sessionId, segmentName));
    } catch (TranscodeException _) {
      return false;
    }
  }

  @Override
  public PreparedSegment prepareSegment(UUID sessionId, String segmentName, byte[] data) {
    try {
      Files.createDirectories(baseDir);
      var temporary = writeTemporarySegment(data);
      return new LocalPreparedSegment(sessionId, segmentName, temporary);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to prepare segment: " + segmentName, e);
    }
  }

  private Path writeTemporarySegment(byte[] data) throws IOException {
    var temporary = Files.createTempFile(baseDir, ".upload-", ".tmp");
    try {
      Files.write(temporary, data);
      return temporary;
    } catch (IOException | RuntimeException e) {
      deleteAfterFailedPreparation(temporary, e);
      throw e;
    }
  }

  private static void deleteAfterFailedPreparation(Path temporary, Exception preparationFailure) {
    try {
      Files.deleteIfExists(temporary);
    } catch (IOException cleanupFailure) {
      preparationFailure.addSuppressed(cleanupFailure);
    }
  }

  private final class LocalPreparedSegment implements PreparedSegment {

    private final UUID sessionId;
    private final String segmentName;
    private final Path temporary;

    private LocalPreparedSegment(UUID sessionId, String segmentName, Path temporary) {
      this.sessionId = sessionId;
      this.segmentName = segmentName;
      this.temporary = temporary;
    }

    @Override
    public void publish() {
      getOutputDirectory(sessionId);
      var segmentPath = resolveSegmentPath(sessionId, segmentName);
      try {
        Files.createDirectories(segmentPath.getParent());
        moveIntoPlace(temporary, segmentPath);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to store segment: " + segmentName, e);
      }
    }

    @Override
    public void close() {
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to clean up segment upload: " + segmentName, e);
      }
    }
  }

  public Path getOutputDirectory(UUID sessionId) {
    return sessionDirs.computeIfAbsent(sessionId, this::createSessionDirectory);
  }

  public Path getOutputDirectory(UUID sessionId, String variantLabel) {
    var sessionDir = getOutputDirectory(sessionId);
    var variantDir = sessionDir.resolve(variantLabel);

    try {
      Files.createDirectories(variantDir);
      return variantDir;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create variant directory", e);
    }
  }

  @Override
  public void deleteSession(UUID sessionId) {
    var dir = sessionDirs.remove(sessionId);
    if (dir != null && Files.exists(dir)) {
      deleteDirectoryRecursively(dir);
    }
  }

  public void shutdown() {
    sessionDirs.forEach(
        (id, dir) -> {
          if (Files.exists(dir)) {
            deleteDirectoryRecursively(dir);
          }
        });
    sessionDirs.clear();
  }

  private Path resolveSegmentPath(UUID sessionId, String segmentName) {
    var dir = sessionDirs.get(sessionId);
    if (dir == null) {
      throw new TranscodeException("No output directory for session: " + sessionId);
    }

    var resolved = dir.resolve(segmentName).normalize();
    if (!resolved.startsWith(dir)) {
      throw new InvalidSegmentPathException(segmentName);
    }

    return resolved;
  }

  private Path createSessionDirectory(UUID sessionId) {
    var dir = baseDir.resolve(sessionId.toString());

    try {
      Files.createDirectories(dir);
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create session directory", e);
    }
  }

  private static void moveIntoPlace(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException _) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void deleteDirectoryRecursively(Path dir) {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (var entry : stream) {
        if (!Files.isDirectory(entry)) {
          Files.deleteIfExists(entry);
          continue;
        }
        deleteDirectoryRecursively(entry);
      }
      Files.deleteIfExists(dir);
    } catch (IOException e) {
      log.warn("Failed to clean up session directory: {}", dir, e);
    }
  }
}
