package com.streamarr.server.services.filepath;

import java.nio.file.Files;
import java.nio.file.Path;

/** Finds soft links on a path without following them. */
public final class FilepathSoftLinks {

  private FilepathSoftLinks() {}

  /**
   * Whether {@code path} is a soft link, or a folder that holds it is one, counting only the
   * folders from {@code first} down to it.
   */
  public static boolean hasSoftLinkFrom(Path first, Path path) {
    if (Files.isSymbolicLink(path)) {
      return true;
    }

    for (var folder = path.getParent();
        folder != null && folder.startsWith(first);
        folder = folder.getParent()) {
      if (Files.isSymbolicLink(folder)) {
        return true;
      }
    }

    return false;
  }
}
