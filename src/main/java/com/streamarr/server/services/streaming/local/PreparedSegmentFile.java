package com.streamarr.server.services.streaming.local;

import com.streamarr.server.services.streaming.SegmentPublication;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;

final class PreparedSegmentFile implements AutoCloseable {

  private static final String UPLOAD_PREFIX = ".upload-";
  private static final String UPLOAD_SUFFIX = ".tmp";
  // Another file holds a random 64-bit name only by rare chance, so a few new names are enough.
  private static final int UPLOAD_NAME_ATTEMPTS = 8;
  private static final SecureRandom UPLOAD_NAMES = new SecureRandom();

  interface FileOperations {
    /**
     * Creates a new file at {@code path} and opens it for writing. Fails with {@link
     * FileAlreadyExistsException} when any entry holds the name, a soft link included, and never
     * follows that entry.
     */
    WritableByteChannel createNew(Path path) throws IOException;

    /** Fails, moving nothing, on a volume that cannot move a file atomically. */
    void moveAtomically(Path source, Path target) throws IOException;

    /** Fails when the file system cannot say whether a file is at {@code path}. */
    boolean notExists(Path path) throws IOException;

    boolean hasSameContent(Path first, Path second) throws IOException;

    void delete(Path path) throws IOException;
  }

  private enum NioFileOperations implements FileOperations {
    INSTANCE;

    private static final Set<OpenOption> NEW_FILE_FOR_WRITING =
        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    @Override
    public WritableByteChannel createNew(Path path) throws IOException {
      // A published segment keeps these permissions, so only the server's user can read it.
      if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
        return Files.newByteChannel(path, NEW_FILE_FOR_WRITING, OWNER_ONLY);
      }

      return Files.newByteChannel(path, NEW_FILE_FOR_WRITING);
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

  /** Whether {@code fileName} is the name that an upload carries until it is published. */
  static boolean isUploadName(String fileName) {
    return fileName.startsWith(UPLOAD_PREFIX) && fileName.endsWith(UPLOAD_SUFFIX);
  }

  static PreparedSegmentFile create(Path directory) throws IOException {
    return create(NioFileOperations.INSTANCE, directory);
  }

  /**
   * Creates the upload's file in {@code directory} under a new name, {@code .upload-} followed by
   * digits and {@code .tmp}. Every later write goes through the file opened at its creation, so a
   * soft link that later takes the name receives none of the upload's bytes.
   */
  static PreparedSegmentFile create(@NonNull FileOperations files, Path directory)
      throws IOException {
    for (var attempt = 0; attempt < UPLOAD_NAME_ATTEMPTS; attempt++) {
      var temporary = directory.resolve(newUploadName());
      var content = createUnlessTheNameIsTaken(files, temporary);
      if (content.isPresent()) {
        return new PreparedSegmentFile(files, temporary, content.get());
      }
    }

    throw new FileAlreadyExistsException(
        directory.toString(), null, "Every generated upload name is taken");
  }

  private static String newUploadName() {
    return UPLOAD_PREFIX + Long.toUnsignedString(UPLOAD_NAMES.nextLong()) + UPLOAD_SUFFIX;
  }

  private static Optional<WritableByteChannel> createUnlessTheNameIsTaken(
      FileOperations files, Path temporary) throws IOException {
    try {
      return Optional.of(files.createNew(temporary));
    } catch (FileAlreadyExistsException _) {
      return Optional.empty();
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
