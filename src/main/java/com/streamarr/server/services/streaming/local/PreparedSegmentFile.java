package com.streamarr.server.services.streaming.local;

import com.streamarr.server.services.streaming.SegmentPublication;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import lombok.NonNull;

final class PreparedSegmentFile implements AutoCloseable {

  interface FileOperations {
    Path createTemporary(Path directory) throws IOException;

    WritableByteChannel openForWriting(Path path) throws IOException;

    /** Fails, moving nothing, on a volume that cannot move a file atomically. */
    void moveAtomically(Path source, Path target) throws IOException;

    /** Fails when the file system cannot say whether a file is at {@code path}. */
    boolean notExists(Path path) throws IOException;

    boolean hasSameContent(Path first, Path second) throws IOException;

    void delete(Path path) throws IOException;
  }

  private enum NioFileOperations implements FileOperations {
    INSTANCE;

    @Override
    public Path createTemporary(Path directory) throws IOException {
      return Files.createTempFile(directory, ".upload-", ".tmp");
    }

    @Override
    public WritableByteChannel openForWriting(Path path) throws IOException {
      return Files.newByteChannel(path, StandardOpenOption.WRITE);
    }

    @Override
    public void moveAtomically(Path source, Path target) throws IOException {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public boolean notExists(Path path) throws IOException {
      // Files.notExists answers false for a failed lookup as well as for a stored file.
      try {
        Files.readAttributes(path, BasicFileAttributes.class);
        return false;
      } catch (NoSuchFileException _) {
        return true;
      }
    }

    @Override
    public boolean hasSameContent(Path first, Path second) throws IOException {
      return Files.mismatch(first, second) == -1L;
    }

    @Override
    public void delete(Path path) throws IOException {
      Files.deleteIfExists(path);
    }
  }

  private final FileOperations files;
  private final Path temporary;
  private final WritableByteChannel content;

  private PreparedSegmentFile(FileOperations files, Path temporary, WritableByteChannel content) {
    this.files = files;
    this.temporary = temporary;
    this.content = content;
  }

  static PreparedSegmentFile create(Path directory) throws IOException {
    return create(NioFileOperations.INSTANCE, directory);
  }

  static PreparedSegmentFile create(@NonNull FileOperations files, Path directory)
      throws IOException {
    var temporary = files.createTemporary(directory);
    try {
      return new PreparedSegmentFile(files, temporary, files.openForWriting(temporary));
    } catch (IOException | RuntimeException preparationFailure) {
      try {
        files.delete(temporary);
      } catch (IOException cleanupFailure) {
        preparationFailure.addSuppressed(cleanupFailure);
      }
      throw preparationFailure;
    }
  }

  /** Writes the bytes after those already appended. */
  void append(ByteBuffer bytes) throws IOException {
    while (bytes.hasRemaining()) {
      content.write(bytes);
    }
  }

  /**
   * Publishes when nothing is stored at {@code target} yet; otherwise leaves the stored file
   * untouched. Answers whether it published. Callers serialize publications to one target.
   */
  boolean publishWhenNothingIsStored(Path target) throws IOException {
    content.close();
    if (!files.notExists(target)) {
      return false;
    }

    publishTo(target);
    return true;
  }

  private void publishTo(Path target) throws IOException {
    // Never a plain move: one that fails can leave part of the file at the target, and the next
    // publication would keep that part as the stored segment.
    files.moveAtomically(temporary, target);
  }

  /**
   * Publishes when nothing is stored at {@code target} yet; otherwise leaves the stored file
   * untouched and reports whether it holds the same bytes. Callers serialize publications to one
   * target.
   */
  SegmentPublication publishUnlessStored(Path target) throws IOException {
    if (publishWhenNothingIsStored(target)) {
      return SegmentPublication.PUBLISHED;
    }

    if (files.hasSameContent(temporary, target)) {
      return SegmentPublication.PUBLISHED;
    }

    return SegmentPublication.INITIALIZATION_SEGMENT_DIFFERS;
  }

  @Override
  public void close() throws IOException {
    try (content) {
      files.delete(temporary);
    }
  }
}
