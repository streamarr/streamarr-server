package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SourceFileSnapshots {

  /** Reads the source's size and modification time. Empty when the source no longer exists. */
  static Optional<SourceFileSnapshot> read(Path path) throws IOException {
    try {
      return Optional.of(
          SourceFileSnapshot.of(Files.readAttributes(path, BasicFileAttributes.class)));
    } catch (NoSuchFileException _) {
      return Optional.empty();
    }
  }
}
