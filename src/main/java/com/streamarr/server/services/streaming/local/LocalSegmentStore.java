package com.streamarr.server.services.streaming.local;

import com.google.common.io.MoreFiles;
import com.streamarr.server.exceptions.InvalidSegmentPathException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.exceptions.UnsupportedSegmentFolderException;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.SegmentStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LocalSegmentStore implements SegmentStore {

  // The mark in a stream session's directory, and in each folder that the store creates in it, that
  // shows a later start that a store created the folder.
  static final String SESSION_MARK_NAME = ".streamarr-stream-session";

  private final Path baseDir;
  private final SegmentFolderLock lock;
  private final ConcurrentHashMap<UUID, Path> sessionDirs = new ConcurrentHashMap<>();
  private final MutexFactory<Path> initializationSegmentLocks = new MutexFactory<>();

  public LocalSegmentStore(Path baseDir, @NonNull SegmentFolderLock lock) {
    if (!deletesWithoutFollowingSoftLinks(baseDir)) {
      throw new UnsupportedSegmentFolderException(baseDir);
    }

    this.baseDir = baseDir;
    this.lock = lock;
  }

  // MoreFiles.deleteRecursively deletes nothing on a file system without secure directory streams.
  private static boolean deletesWithoutFollowingSoftLinks(Path segmentFolder) {
    try (var entries = Files.newDirectoryStream(Files.createDirectories(segmentFolder))) {
      return entries instanceof SecureDirectoryStream;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to open segment folder: " + segmentFolder, e);
    }
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
      var temporary = PreparedSegmentFile.create(heldFolder(), data);
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
      var sessionDir = getOutputDirectory(sessionId);
      var segmentPath = resolveSegmentPath(sessionId, segmentName);
      try {
        createMarkedFolders(sessionDir, segmentPath.getParent());
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
      createMarkedFolders(sessionDir, variantDir);
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
    try {
      var dir = heldFolder().resolve(sessionId.toString());
      createMarkedFolder(dir);
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create session directory", e);
    }
  }

  // Creates and marks each missing folder between the stream session's directory and the folder.
  private static void createMarkedFolders(Path sessionDir, Path folder) throws IOException {
    var created = sessionDir;
    for (var name : sessionDir.relativize(folder)) {
      created = created.resolve(name);
      createMarkedFolder(created);
    }
  }

  // The store marks only a folder that it creates. A folder that is already there may be a file
  // system mounted there, whose files no start may delete.
  private static void createMarkedFolder(Path folder) throws IOException {
    try {
      Files.createDirectory(folder);
    } catch (FileAlreadyExistsException e) {
      if (Files.isDirectory(folder)) {
        return;
      }

      throw e;
    }

    Files.write(
        folder.resolve(SESSION_MARK_NAME),
        new byte[0],
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS);
  }

  // The folder of each write, whose lock the store holds from its first write, so that no other
  // server process's start deletes what it writes there.
  private Path heldFolder() throws IOException {
    Files.createDirectories(baseDir);
    return lock.holdShared(baseDir);
  }

  private void deleteDirectoryRecursively(Path dir) {
    try {
      // Deletes a soft link itself, never what it points to.
      MoreFiles.deleteRecursively(dir);
    } catch (IOException e) {
      log.warn("Failed to clean up session directory: {}", dir, e);
    }
  }
}
