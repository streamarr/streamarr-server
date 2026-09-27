package com.streamarr.server.services.library.walk;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Throwaway benchmark lever: how a scan enumerates a library root. Exactly one implementation is a
 * bean, selected by {@code poc.walk} ({@code serial} when unset).
 */
public interface LibraryWalk {

  /** The {@code poc.walk} value that selects this implementation. */
  String name();

  /**
   * Lazily streams the entries under {@code root}. The stream contains at least every regular file
   * under {@code root} that the scan does not ignore; it may contain other entries, which the scan
   * filters out as before. The caller must close the stream.
   *
   * @throws IOException if {@code root} cannot be read, as {@code Files.walk} does
   */
  Stream<Path> walk(Path root) throws IOException;
}
