package com.streamarr.server.services.streaming.local;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

final class PreparedSegmentFile implements AutoCloseable {

  interface FileOperations {
    Path createTemporary(Path directory) throws IOException;

    void write(Path path, byte[] data) throws IOException;

    void moveAtomically(Path source, Path target) throws IOException;

    void moveReplacing(Path source, Path target) throws IOException;

    void delete(Path path) throws IOException;
  }

  private enum NioFileOperations implements FileOperations {
    INSTANCE;

    @Override
    public Path createTemporary(Path directory) throws IOException {
      return Files.createTempFile(directory, ".upload-", ".tmp");
    }

    @Override
    public void write(Path path, byte[] data) throws IOException {
      Files.write(path, data);
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
    public void delete(Path path) throws IOException {
      Files.deleteIfExists(path);
    }
  }

  private final FileOperations files;
  private final Path temporary;

  private PreparedSegmentFile(FileOperations files, Path temporary) {
    this.files = files;
    this.temporary = temporary;
  }

  static PreparedSegmentFile create(Path directory, byte[] data) throws IOException {
    return create(NioFileOperations.INSTANCE, directory, data);
  }

  static PreparedSegmentFile create(FileOperations files, Path directory, byte[] data)
      throws IOException {
    Objects.requireNonNull(files);
    var temporary = files.createTemporary(directory);
    try {
      files.write(temporary, data);
      return new PreparedSegmentFile(files, temporary);
    } catch (IOException | RuntimeException preparationFailure) {
      try {
        files.delete(temporary);
      } catch (IOException cleanupFailure) {
        preparationFailure.addSuppressed(cleanupFailure);
      }
      throw preparationFailure;
    }
  }

  void publishTo(Path target) throws IOException {
    try {
      files.moveAtomically(temporary, target);
    } catch (AtomicMoveNotSupportedException _) {
      files.moveReplacing(temporary, target);
    }
  }

  @Override
  public void close() throws IOException {
    files.delete(temporary);
  }
}
