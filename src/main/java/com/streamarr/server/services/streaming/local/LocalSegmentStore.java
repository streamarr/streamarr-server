package com.streamarr.server.services.streaming.local;

import com.streamarr.server.exceptions.InvalidSegmentPathException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.streaming.SegmentStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LocalSegmentStore implements SegmentStore {

  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

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

  @Override
  public boolean waitForSegment(UUID sessionId, String segmentName, Duration timeout) {
    var segmentPath = resolveSegmentPath(sessionId, segmentName);
    var deadline = System.nanoTime() + timeout.toNanos();

    while (System.nanoTime() < deadline) {
      if (Files.exists(segmentPath)) {
        return true;
      }
      try {
        Thread.sleep(POLL_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return Files.exists(segmentPath);
  }

  @Override
  public boolean segmentExists(UUID sessionId, String segmentName) {
    try {
      return Files.exists(resolveSegmentPath(sessionId, segmentName));
    } catch (TranscodeException e) {
      return false;
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

  private void deleteDirectoryRecursively(Path dir) {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (var entry : stream) {
        if (Files.isDirectory(entry)) {
          deleteDirectoryRecursively(entry);
        } else {
          Files.deleteIfExists(entry);
        }
      }
      Files.deleteIfExists(dir);
    } catch (IOException e) {
      log.warn("Failed to clean up session directory: {}", dir, e);
    }
  }
}
