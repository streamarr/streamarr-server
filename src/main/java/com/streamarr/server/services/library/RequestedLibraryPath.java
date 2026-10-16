package com.streamarr.server.services.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.IntStream;
import lombok.NonNull;

/**
 * The normalized path given to addLibrary, and whether the file system that holds the folder
 * ignores letter case.
 */
record RequestedLibraryPath(@NonNull Path path, boolean ignoresLetterCase) {

  /**
   * Reads the file system to learn whether it ignores letter case, so the path comparison under the
   * addLibrary lock reads no file. Call it before addLibrary takes its lock.
   */
  static RequestedLibraryPath probe(Path folder) {
    return new RequestedLibraryPath(folder, ignoresLetterCaseAt(folder));
  }

  // The deepest folder name with a letter decides: a file system that ignores letter case finds
  // the same folder under that name with the case of every letter swapped.
  private static boolean ignoresLetterCaseAt(Path folder) {
    return IntStream.iterate(folder.getNameCount(), count -> count > 0, count -> count - 1)
        .mapToObj(count -> folder.getRoot().resolve(folder.subpath(0, count)))
        .flatMap(ancestor -> swappedSpelling(ancestor).stream())
        .findFirst()
        .map(TwoSpellings::isSameFolder)
        .orElse(false);
  }

  private static Optional<TwoSpellings> swappedSpelling(Path folder) {
    var name = folder.getFileName().toString();
    var swapped =
        name.codePoints()
            .map(RequestedLibraryPath::swapLetterCase)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
    if (swapped.equals(name)) {
      return Optional.empty();
    }

    return Optional.of(new TwoSpellings(folder, folder.resolveSibling(swapped)));
  }

  private static int swapLetterCase(int codePoint) {
    if (Character.isUpperCase(codePoint)) {
      return Character.toLowerCase(codePoint);
    }

    return Character.toUpperCase(codePoint);
  }

  private record TwoSpellings(Path folder, Path swapped) {

    boolean isSameFolder() {
      try {
        return Files.isSameFile(folder, swapped);
      } catch (IOException _) {
        return false;
      }
    }
  }
}
