package com.streamarr.server.services.streaming.local;

import com.streamarr.server.exceptions.InvalidSegmentPathException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StoredSegment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LocalSegmentStore implements SegmentStore {

  private final Path baseDir;
  private final ConcurrentHashMap<UUID, Path> sessionDirs = new ConcurrentHashMap<>();
  private final MutexFactory<Path> publicationLocks = new MutexFactory<>();

  public LocalSegmentStore(Path baseDir) {
    this.baseDir = baseDir;
  }

  @Override
  public Optional<StoredSegment> openSegment(UUID sessionId, String segmentName) {
    var segmentPath = findSegmentPath(sessionId, segmentName);
    if (segmentPath.isEmpty()) {
      return Optional.empty();
    }

    try {
      return Optional.of(OpenedSegmentFile.open(segmentPath.get()));
    } catch (NoSuchFileException _) {
      return Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to open segment: " + segmentName, e);
    }
  }

  @Override
  public boolean segmentExists(UUID sessionId, String segmentName) {
    return findSegmentPath(sessionId, segmentName).filter(Files::exists).isPresent();
  }

  @Override
  public PreparedSegment prepareSegment(UUID sessionId, String segmentName) {
    try {
      Files.createDirectories(baseDir);
      var temporary = PreparedSegmentFile.create(baseDir);
      return new LocalPreparedSegment(sessionId, segmentName, temporary);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to prepare segment: " + segmentName, e);
    }
  }

  private final class LocalPreparedSegment implements PreparedSegment {

    private final UUID sessionId;
    private final String segmentName;
    private final PreparedSegmentFile temporary;

    private LocalPreparedSegment(
        UUID sessionId, String segmentName, PreparedSegmentFile temporary) {
      this.sessionId = sessionId;
      this.segmentName = segmentName;
      this.temporary = temporary;
    }

    @Override
    public void append(ByteBuffer bytes) {
      try {
        temporary.append(bytes);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to write segment upload: " + segmentName, e);
      }
    }

    @Override
    public SegmentPublication publish() {
      getOutputDirectory(sessionId);
      var segmentPath = resolveSegmentPath();
      try {
        Files.createDirectories(segmentPath.getParent());
        return publishOnce(segmentPath);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to store segment: " + segmentName, e);
      }
    }

    private Path resolveSegmentPath() {
      return findSegmentPath(sessionId, segmentName)
          .orElseThrow(
              () -> new TranscodeException("No output directory for session: " + sessionId));
    }

    private SegmentPublication publishOnce(Path segmentPath) throws IOException {
      // Only this store writes a session's directory, so serializing publications per path makes
      // the first one create-if-absent on any volume that can rename.
      var lock = publicationLocks.getMutex(segmentPath);
      lock.lock();
      try {
        return publishInitializationOrMediaSegment(segmentPath);
      } finally {
        lock.unlock();
      }
    }

    private SegmentPublication publishInitializationOrMediaSegment(Path segmentPath)
        throws IOException {
      if (SegmentNames.isInitSegment(segmentName)) {
        return temporary.publishUnlessStored(segmentPath);
      }

      temporary.publishWhenNothingIsStored(segmentPath);
      return SegmentPublication.PUBLISHED;
    }

    @Override
    public void close() {
      try {
        temporary.close();
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

  /**
   * Empty until the session's first published segment creates the directory — in remote mode that's
   * the worker's first upload, so absence is a normal pre-startup state, not an error.
   */
  private Optional<Path> findSegmentPath(UUID sessionId, String segmentName) {
    var dir = sessionDirs.get(sessionId);
    if (dir == null) {
      return Optional.empty();
    }

    var resolved = dir.resolve(segmentName).normalize();
    if (!resolved.startsWith(dir)) {
      throw new InvalidSegmentPathException(segmentName);
    }

    return Optional.of(resolved);
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
