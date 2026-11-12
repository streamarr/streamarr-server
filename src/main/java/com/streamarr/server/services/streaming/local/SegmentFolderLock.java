package com.streamarr.server.services.streaming.local;

import com.streamarr.server.exceptions.UnsupportedSegmentFolderException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.Set;

/**
 * The lock by which server processes that name one segment folder keep each other from deleting
 * what they wrote there. A server process holds the lock shared from its first write into the
 * folder until it ends. A start deletes leftovers only while it holds the lock alone, so only while
 * no other running server process has written into the folder. The operating system releases a
 * process's lock when the process ends, however it ends.
 */
public final class SegmentFolderLock implements AutoCloseable {

  static final String NAME = ".streamarr-segment-folder.lock";

  // Never through a soft link in the lock's place.
  private static final Set<OpenOption> OPTIONS =
      Set.of(
          StandardOpenOption.CREATE,
          StandardOpenOption.READ,
          StandardOpenOption.WRITE,
          LinkOption.NOFOLLOW_LINKS);

  private FileChannel channel;
  // The real path of the folder whose lock the channel holds, read once when the channel opens: a
  // soft link on the folder's path that is pointed elsewhere later leads no write out of it.
  private Path lockedFolder;
  private boolean heldShared;

  /**
   * Runs {@code work} while this process holds the opened folder's lock alone, then releases it.
   * When another server process holds the lock, it runs nothing and returns empty. It never waits.
   * It opens the lock's file through the opened folder, never through a path, and {@code folder} is
   * that folder's real path.
   */
  synchronized <T> Optional<T> whileAlone(
      SecureDirectoryStream<Path> opened, Path folder, Work<T> work) throws IOException {
    var alone = tryLockAlone(opened, folder);
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
   * alone. It returns the folder whose lock it holds, the only folder that this process may write
   * into: the real path of the folder that this lock was first used for.
   */
  public synchronized Path holdShared(Path folder) throws IOException {
    if (heldShared) {
      return lockedFolder;
    }

    try {
      channel(folder).lock(0, Long.MAX_VALUE, true);
    } catch (OverlappingFileLockException _) {
      // Another lock of this Java process already holds the folder for this process.
    }

    heldShared = true;
    return lockedFolder;
  }

  private Optional<FileLock> tryLockAlone(SecureDirectoryStream<Path> opened, Path folder)
      throws IOException {
    if (channel == null) {
      lockedFolder = folder;
      channel = openedChannel(opened.newByteChannel(folder.getFileSystem().getPath(NAME), OPTIONS));
    }

    try {
      return Optional.ofNullable(channel.tryLock());
    } catch (OverlappingFileLockException _) {
      // Another lock of this Java process holds the folder: it counts as another server process.
      return Optional.empty();
    }
  }

  // A secure directory stream opens a file channel, as the JDK's and Jimfs's do.
  private FileChannel openedChannel(SeekableByteChannel opened) throws IOException {
    if (!(opened instanceof FileChannel fileChannel)) {
      opened.close();
      throw new UnsupportedSegmentFolderException(lockedFolder);
    }

    return fileChannel;
  }

  private FileChannel channel(Path folder) throws IOException {
    if (channel == null) {
      lockedFolder = folder.toRealPath();
      channel = FileChannel.open(lockedFolder.resolve(NAME), OPTIONS);
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
