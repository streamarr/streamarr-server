package com.streamarr.server.services.streaming.local;

import com.streamarr.server.services.streaming.SegmentPublication;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import lombok.NonNull;

final class PreparedSegmentFile implements AutoCloseable {

  interface FileOperations {
    Path createTemporary(Path directory) throws IOException;

    WritableByteChannel openForWriting(Path path) throws IOException;

    void moveAtomically(Path source, Path target) throws IOException;

    void moveReplacing(Path source, Path target) throws IOException;

    boolean notExists(Path path);

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
    public void moveReplacing(Path source, Path target) throws IOException {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public boolean notExists(Path path) {
      return Files.notExists(path);
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

  void publishTo(Path target) throws IOException {
    content.close();
    try {
      files.moveAtomically(temporary, target);
    } catch (AtomicMoveNotSupportedException _) {
      files.moveReplacing(temporary, target);
    }
  }

  /**
   * Publishes when nothing is stored at {@code target} yet; otherwise leaves the stored file
   * untouched. Callers serialize publications to one target.
   */
  void publishWhenNothingIsStored(Path target) throws IOException {
    content.close();
    if (files.notExists(target)) {
      publishTo(target);
    }
  }

  /**
   * Publishes when nothing is stored at {@code target} yet; otherwise leaves the stored file
   * untouched and reports whether it holds the same bytes. Callers serialize publications to one
   * target.
   */
  SegmentPublication publishUnlessStored(Path target) throws IOException {
    content.close();
    if (files.notExists(target)) {
      publishTo(target);
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
