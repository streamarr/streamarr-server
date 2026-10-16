package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.services.filepath.FilepathCodec;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Finds the libraries whose path conflicts with the path given to addLibrary. Paths compare
 * lexically, after normalization, by whole names. Where the file system ignores letter case, the
 * names compare without letter case. No file is read while addLibrary holds its lock.
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
    var ignoreLetterCase = requested.ignoresLetterCase();
    if (startsWith(path, libraryPath, ignoreLetterCase)
        && startsWith(libraryPath, path, ignoreLetterCase)) {
      return Optional.of(new AddLibraryRejection.PathAlreadyRegistered(library, libraryPath));
    }

    if (startsWith(path, libraryPath, ignoreLetterCase)) {
      return Optional.of(
          new AddLibraryRejection.PathOverlap(LibraryPathOverlap.INSIDE, library, libraryPath));
    }

    if (startsWith(libraryPath, path, ignoreLetterCase)) {
      return Optional.of(
          new AddLibraryRejection.PathOverlap(LibraryPathOverlap.CONTAINS, library, libraryPath));
    }

    return Optional.empty();
  }

  // Compares whole names, so /media/movies2 does not start with /media/movies.
  private static boolean startsWith(Path path, Path prefix, boolean ignoreLetterCase) {
    if (prefix.getNameCount() > path.getNameCount()) {
      return false;
    }

    return IntStream.range(0, prefix.getNameCount())
        .allMatch(
            index -> isSameName(path.getName(index), prefix.getName(index), ignoreLetterCase));
  }

  private static boolean isSameName(Path name, Path otherName, boolean ignoreLetterCase) {
    if (ignoreLetterCase) {
      return name.toString().equalsIgnoreCase(otherName.toString());
    }

    return name.equals(otherName);
  }
}
