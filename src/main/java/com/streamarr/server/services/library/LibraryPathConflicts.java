package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.services.filepath.FilepathCodec;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Finds the libraries whose path conflicts with the path given to addLibrary. Paths compare
 * lexically, after normalization, as {@link Path} compares them, like the unique index on the
 * library path and the file watcher. No file is read while addLibrary holds its lock.
 */
final class LibraryPathConflicts {

  private LibraryPathConflicts() {}

  static List<AddLibraryRejection> conflictsOf(
      FileSystem fileSystem, RequestedLibraryPath requested, List<Library> libraries) {
    return libraries.stream()
        .flatMap(library -> conflictOf(fileSystem, requested, library).stream())
        .toList();
  }

  private static Optional<AddLibraryRejection> conflictOf(
      FileSystem fileSystem, RequestedLibraryPath requested, Library library) {
    var libraryPath =
        FilepathCodec.decode(fileSystem, library.getFilepathUri()).toAbsolutePath().normalize();
    var path = requested.path();
    if (path.equals(libraryPath)) {
      return Optional.of(new AddLibraryRejection.PathAlreadyRegistered(library, libraryPath));
    }

    // Path.startsWith compares whole names, so /media/movies2 does not start with /media/movies.
    if (path.startsWith(libraryPath)) {
      return Optional.of(
          new AddLibraryRejection.PathOverlap(LibraryPathOverlap.INSIDE, library, libraryPath));
    }

    if (libraryPath.startsWith(path)) {
      return Optional.of(
          new AddLibraryRejection.PathOverlap(LibraryPathOverlap.CONTAINS, library, libraryPath));
    }

    return Optional.empty();
  }
}
