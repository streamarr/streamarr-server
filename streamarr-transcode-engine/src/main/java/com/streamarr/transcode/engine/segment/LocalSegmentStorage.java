package com.streamarr.transcode.engine.segment;

import com.streamarr.transcode.engine.error.TranscodeException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LocalSegmentStorage {

  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  private final Path baseDir;
  private final ConcurrentHashMap<UUID, Path> sessionDirs = new ConcurrentHashMap<>();

  public LocalSegmentStorage(Path baseDir) {
    this.baseDir = baseDir;
  }

  public byte[] readSegment(UUID sessionId, String segmentName) {
    var segmentPath = resolveSegmentPath(sessionId, segmentName);

    try {
      return Files.readAllBytes(segmentPath);
    } catch (NoSuchFileException exception) {
      throw new TranscodeException("Segment not found: " + segmentName, exception);
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to read segment: " + segmentName, exception);
    }
  }

  public boolean waitForSegment(UUID sessionId, String segmentName, Duration timeout) {
    var segmentPath = resolveSegmentPath(sessionId, segmentName);
    var deadline = System.nanoTime() + timeout.toNanos();

    while (System.nanoTime() < deadline) {
      if (Files.exists(segmentPath)) {
        return true;
      }
      try {
        Thread.sleep(POLL_INTERVAL.toMillis());
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return Files.exists(segmentPath);
  }

  public boolean segmentExists(UUID sessionId, String segmentName) {
    try {
      return Files.exists(resolveSegmentPath(sessionId, segmentName));
    } catch (TranscodeException exception) {
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
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to create variant directory", exception);
    }
  }

  public Set<UUID> snapshotStoredSessionIds() {
    var sessionIds = new HashSet<UUID>();
    try (var entries = Files.newDirectoryStream(baseDir)) {
      for (var entry : entries) {
        storedSessionId(entry).ifPresent(sessionIds::add);
      }
    } catch (NoSuchFileException _) {
      return Set.of();
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to discover stored stream sessions", exception);
    }
    return Set.copyOf(sessionIds);
  }

  public void deleteSession(UUID sessionId) {
    sessionDirs.remove(sessionId);
    var dir = baseDir.resolve(sessionId.toString());
    if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      deleteDirectoryRecursively(dir);
    }
  }

  public void shutdown() {
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
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to create session directory", exception);
    }
  }

  private static Optional<UUID> storedSessionId(Path entry) {
    var name = entry.getFileName().toString();
    try {
      var sessionId = UUID.fromString(name);
      return sessionId.toString().equals(name) ? Optional.of(sessionId) : Optional.empty();
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }

  private void deleteDirectoryRecursively(Path dir) {
    try {
      Files.walkFileTree(
          dir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException {
              Files.deleteIfExists(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                throws IOException {
              if (exception != null) {
                throw exception;
              }
              Files.deleteIfExists(directory);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to clean up session directory: " + dir, exception);
    }
  }
}
