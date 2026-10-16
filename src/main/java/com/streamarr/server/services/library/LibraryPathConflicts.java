package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.services.filepath.FilepathCodec;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Finds the libraries whose path conflicts with the path given to addLibrary. Paths compare
 * lexically, after normalization, with the equality of the server's filesystem: no file is read
 * while addLibrary holds its lock.
 */
final class LibraryPathConflicts {

  private LibraryPathConflicts() {}

  static List<AddLibraryRejection> conflictsOf(
      FileSystem fileSystem, Path path, List<Library> libraries) {
    return libraries.stream()
        .flatMap(library -> conflictOf(fileSystem, path, library).stream())
        .toList();
  }

  private static Optional<AddLibraryRejection> conflictOf(
      FileSystem fileSystem, Path path, Library library) {
    var libraryPath =
        FilepathCodec.decode(fileSystem, library.getFilepathUri()).toAbsolutePath().normalize();
    if (path.equals(libraryPath)) {
      return Optional.of(new AddLibraryRejection.PathAlreadyRegistered(library, libraryPath));
    }

    return Optional.empty();
  }
}
