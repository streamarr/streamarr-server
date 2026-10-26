package com.streamarr.server.services.filepath;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes a path to the names that its file system stores, so a folder that the server stores
 * and compares has one form. Where the file system ignores letter case or Unicode normalization,
 * each name becomes the stored name. Dot segments are removed by the path text, and symbolic links
 * stay unresolved. Where the file system gives no file key, as on Windows, the names stay as given.
 */
public final class FilepathNameNormalizer {

  private FilepathNameNormalizer() {}

  /**
   * @throws IOException when the path does not exist, a folder on the path cannot be listed or an
   *     entry's attributes cannot be read, or no single stored name has a name's file key
   */
  public static Path normalizeToFileSystemNames(Path path) throws IOException {
    var absolute = path.toAbsolutePath().normalize();
    var normalized = absolute.getRoot();
    for (var name : absolute) {
      normalized = storedEntry(normalized, name);
    }

    return normalized;
  }

  // The JDK replaces names with the stored names only on macOS and Windows, and a Linux file system
  // that ignores letter case answers to any spelling. So the given name is looked up among its
  // folder's entries, and otherwise the one entry with the same file key is taken. A file key that
  // several names share, as hard links do, names no single entry.
  private static Path storedEntry(Path folder, Path name) throws IOException {
    var given = folder.resolve(name);
    var key = fileKeyOf(given);
    var entries = entriesOf(folder);
    if (key == null || entries.stream().anyMatch(entry -> entry.getFileName().equals(name))) {
      return given;
    }

    var sameKey = new ArrayList<Path>();
    for (var entry : entries) {
      if (key.equals(fileKeyOf(entry))) {
        sameKey.add(entry);
      }
    }

    if (sameKey.size() != 1) {
      throw new FileSystemException(given.toString(), null, "No single stored name for this path");
    }

    return sameKey.getFirst();
  }

  private static List<Path> entriesOf(Path folder) throws IOException {
    var entries = new ArrayList<Path>();
    try (var stream = Files.newDirectoryStream(folder)) {
      stream.forEach(entries::add);
    } catch (DirectoryIteratorException exception) {
      throw exception.getCause();
    }

    return entries;
  }

  private static Object fileKeyOf(Path path) throws IOException {
    return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
        .fileKey();
  }
}
