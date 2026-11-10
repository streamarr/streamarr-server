package com.streamarr.server.services.streaming.local;

import com.streamarr.server.exceptions.InvalidSegmentPathException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.SegmentStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LocalSegmentStore implements SegmentStore {

  private static final Pattern SESSION_DIRECTORY_NAME =
      Pattern.compile("\\p{XDigit}{8}(-\\p{XDigit}{4}){3}-\\p{XDigit}{12}");

  private static final String OWNER_LOCK_NAME = ".owner.lock";

  private final Path baseDir;
  private volatile FileChannel folderOwnership;
  private final ConcurrentHashMap<UUID, Path> sessionDirs = new ConcurrentHashMap<>();
  private final MutexFactory<Path> initializationSegmentLocks = new MutexFactory<>();

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
  public boolean segmentExists(UUID sessionId, String segmentName) {
    return findSegmentPath(sessionId, segmentName).filter(Files::exists).isPresent();
  }

  @Override
  public PreparedSegment prepareSegment(UUID sessionId, String segmentName, byte[] data) {
    try {
      Files.createDirectories(baseDir);
      var temporary = PreparedSegmentFile.create(baseDir, data);
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
    public SegmentPublication publish() {
      getOutputDirectory(sessionId);
      var segmentPath = resolveSegmentPath(sessionId, segmentName);
      try {
        Files.createDirectories(segmentPath.getParent());
        if (SegmentNames.isInitSegment(segmentName)) {
          return publishInitialization(segmentPath);
        }

        temporary.publishTo(segmentPath);
        return SegmentPublication.PUBLISHED;
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to store segment: " + segmentName, e);
      }
    }

    private SegmentPublication publishInitialization(Path segmentPath) throws IOException {
      // Only this store writes a session's directory, so serializing publications per path makes
      // the first one create-if-absent on any volume that can rename.
      var lock = initializationSegmentLocks.getMutex(segmentPath);
      lock.lock();
      try {
        return temporary.publishUnlessStored(segmentPath);
      } finally {
        lock.unlock();
      }
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

  /**
   * Deletes what an earlier server process left in the segment folder: its stream sessions'
   * directories and its unpublished uploads. That process's stream sessions ended with it, so
   * nothing else would delete them. Any other entry is not this store's and stays, and so does
   * everything while another running store holds the folder.
   */
  public void reclaimLeftovers() {
    try {
      Files.createDirectories(baseDir);
      if (!tryOwnFolder()) {
        log.warn("Another running server holds the segment folder {}; nothing reclaimed", baseDir);
        return;
      }

      reclaimEntries();
    } catch (IOException e) {
      log.warn("Failed to reclaim the segment folder's leftovers: {}", baseDir, e);
    }
  }

  // The operating system releases the lock when the owning process ends, however it ends, so a
  // lock that can be taken proves that no running server still serves from the folder.
  private boolean tryOwnFolder() throws IOException {
    var channel =
        FileChannel.open(
            baseDir.resolve(OWNER_LOCK_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      if (channel.tryLock() != null) {
        folderOwnership = channel;
        return true;
      }
    } catch (OverlappingFileLockException _) {
      // Another store of this process holds the folder.
    }

    channel.close();
    return false;
  }

  private void reclaimEntries() throws IOException {
    try (var entries = Files.newDirectoryStream(baseDir)) {
      for (var entry : entries) {
        reclaim(entry);
      }
    }
  }

  private void reclaim(Path entry) throws IOException {
    var name = entry.getFileName().toString();
    var isDirectory = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
    if (isDirectory && SESSION_DIRECTORY_NAME.matcher(name).matches()) {
      deleteDirectoryRecursively(entry);
      return;
    }

    if (!isDirectory && PreparedSegmentFile.isTemporaryName(name)) {
      Files.deleteIfExists(entry);
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
    releaseFolder();
  }

  private void releaseFolder() {
    var ownership = folderOwnership;
    folderOwnership = null;
    if (ownership == null) {
      return;
    }

    try {
      ownership.close();
    } catch (IOException e) {
      log.warn("Failed to release the segment folder: {}", baseDir, e);
    }
  }

  private Path resolveSegmentPath(UUID sessionId, String segmentName) {
    return findSegmentPath(sessionId, segmentName)
        .orElseThrow(() -> new TranscodeException("No output directory for session: " + sessionId));
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
