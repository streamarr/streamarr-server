package com.streamarr.server.services.filepath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Spells a path with the names that its file system stores, so a folder that the server stores and
 * compares has one spelling. Where the file system ignores letter case or Unicode normalization,
 * each name becomes the stored name. Dot segments are removed by the path text, and symbolic links
 * stay unresolved. Where the file system gives no file key, as on Windows, the names stay as given.
 */
public final class OnDiskSpelling {

  private OnDiskSpelling() {}

  /**
   * @throws IOException when the path does not exist, or a folder on the path cannot be listed
   */
  public static Path of(Path path) throws IOException {
    var absolute = path.toAbsolutePath().normalize();
    var spelled = absolute.getRoot();
    for (var name : absolute) {
      spelled = storedEntry(spelled, name);
    }

    return spelled;
  }

  // The JDK respells names only on macOS and Windows, and a Linux file system that ignores letter
  // case answers to any spelling. So each name is found among its folder's entries by file key.
  private static Path storedEntry(Path folder, Path name) throws IOException {
    var given = folder.resolve(name);
    var key = fileKeyOf(given);
    try (var entries = Files.newDirectoryStream(folder)) {
      for (var entry : entries) {
        if (entry.getFileName().equals(name) || hasFileKey(entry, key)) {
          return entry;
        }
      }
    }

    return given;
  }

  private static boolean hasFileKey(Path entry, Object key) {
    if (key == null) {
      return false;
    }

    try {
      return key.equals(fileKeyOf(entry));
    } catch (IOException _) {
      // The entry went away while the folder was listed.
      return false;
    }
  }

  private static Object fileKeyOf(Path path) throws IOException {
    return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
        .fileKey();
  }
}
