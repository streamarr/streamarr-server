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
 * their stream sessions. A server process that uses the folder holds the lock shared until it ends.
 * The delete of leftovers at a start holds it alone, so it runs only while no other server process
 * uses the folder. The operating system releases a process's lock when the process ends, however it
 * ends.
 */
public final class SegmentFolderLock implements AutoCloseable {

  static final String NAME = ".streamarr-segment-folder.lock";

  private FileChannel channel;

  /**
   * Runs {@code work} while this process holds the folder's lock alone, then keeps the lock shared.
   * When another server process holds the lock, it runs nothing, keeps the lock shared with that
   * process and returns empty. It waits while another process holds the lock alone.
   */
  <T> Optional<T> whileAlone(Path folder, Work<T> work) throws IOException {
    // Never through a soft link in the lock's place.
    channel =
        FileChannel.open(
            folder.resolve(NAME),
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
    var alone = tryLockAlone();
    if (alone.isEmpty()) {
      holdShared();
      return Optional.empty();
    }

    try {
      return Optional.of(work.run());
    } finally {
      alone.get().release();
      holdShared();
    }
  }

  private Optional<FileLock> tryLockAlone() throws IOException {
    try {
      return Optional.ofNullable(channel.tryLock());
    } catch (OverlappingFileLockException _) {
      // Another lock of this Java process holds the folder: it counts as another server process.
      return Optional.empty();
    }
  }

  private void holdShared() throws IOException {
    try {
      channel.lock(0, Long.MAX_VALUE, true);
    } catch (OverlappingFileLockException _) {
      // Another lock of this Java process already holds the folder for this process.
    }
  }

  /** Releases the lock. A process that ends releases it too. */
  @Override
  public void close() throws IOException {
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
