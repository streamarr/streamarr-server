package com.streamarr.server.services.filepath;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Spells a path with the names that its file system stores, so a folder that the server stores and
 * compares has one spelling. Where the file system ignores letter case or Unicode normalization, as
 * on macOS and Windows, the JDK replaces each name with the stored name. On Linux the JDK returns
 * the path as given. Symbolic links stay unresolved.
 */
public final class OnDiskSpelling {

  private OnDiskSpelling() {}

  /**
   * @throws IOException when the path does not exist, or a folder above it cannot be listed
   */
  public static Path of(Path path) throws IOException {
    return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
  }
}
