package com.streamarr.server.services.filepath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Finds soft links on a path without following them. */
public final class FilepathSoftLinks {

  private FilepathSoftLinks() {}

  /**
   * Whether {@code path} is a soft link, or a folder that holds it is one, counting only the
   * folders from {@code first} down to it.
   *
   * @throws IOException if {@code path} or one of those folders cannot be read
   */
  public static boolean hasSoftLinkFrom(Path first, Path path) throws IOException {
    if (isSoftLink(path)) {
      return true;
    }

    for (var folder = path.getParent();
        folder != null && folder.startsWith(first);
        folder = folder.getParent()) {
      if (isSoftLink(folder)) {
        return true;
      }
    }

    return false;
  }

  // Files.isSymbolicLink answers false when the read fails, which would pass an unread link.
  private static boolean isSoftLink(Path path) throws IOException {
    return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
        .isSymbolicLink();
  }
}
