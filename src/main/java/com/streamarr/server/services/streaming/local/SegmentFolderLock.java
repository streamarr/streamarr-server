package com.streamarr.server.services.streaming.local;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * The lock by which server processes that name one segment folder keep each other from deleting
 * what they wrote there. A server process holds the lock shared from its first write into the
 * folder until it ends. A start deletes leftovers only while it holds the lock alone, so only while
 * no other running server process has written into the folder. The operating system releases a
 * process's lock when the process ends, however it ends.
 */
public final class SegmentFolderLock implements AutoCloseable {

  static final String NAME = ".streamarr-segment-folder.lock";

  private FileChannel channel;
  private boolean heldShared;

  /**
   * Runs {@code work} while this process holds the folder's lock alone, then releases it. When
   * another server process holds the lock, it runs nothing and returns empty. It never waits.
   */
  synchronized <T> Optional<T> whileAlone(Path folder, Work<T> work) throws IOException {
    var alone = tryLockAlone(folder);
    if (alone.isEmpty()) {
      return Optional.empty();
    }

    try {
      return Optional.of(work.run());
    } finally {
      alone.get().release();
    }
  }

  /**
   * Holds the folder's lock shared until this lock closes, so that no other server process's start
   * deletes what this process writes there. It waits while another process's start holds the lock
   * alone.
   */
  public synchronized void holdShared(Path folder) throws IOException {
    if (heldShared) {
      return;
    }

    try {
      channel(folder).lock(0, Long.MAX_VALUE, true);
    } catch (OverlappingFileLockException _) {
      // Another lock of this Java process already holds the folder for this process.
    }

    heldShared = true;
  }

  private Optional<FileLock> tryLockAlone(Path folder) throws IOException {
    try {
      return Optional.ofNullable(channel(folder).tryLock());
    } catch (OverlappingFileLockException _) {
      // Another lock of this Java process holds the folder: it counts as another server process.
      return Optional.empty();
    }
  }

  // Never through a soft link in the lock's place.
  private FileChannel channel(Path folder) throws IOException {
    if (channel == null) {
      channel =
          FileChannel.open(
              folder.resolve(NAME),
              StandardOpenOption.CREATE,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS);
    }

    return channel;
  }

  /** Releases the lock. A process that ends releases it too. */
  @Override
  public synchronized void close() throws IOException {
    if (channel != null) {
      channel.close();
    }
  }

  /** Work that may fail to read or write files. */
  @FunctionalInterface
  interface Work<T> {
    T run() throws IOException;
  }
}
