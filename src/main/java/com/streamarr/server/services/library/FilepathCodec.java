package com.streamarr.server.services.library;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Path;

public final class FilepathCodec {

  private static final char SEPARATOR = '/';

  private FilepathCodec() {}

  public static String encode(Path path) {
    return path.toAbsolutePath().toUri().toString();
  }

  /**
   * Extracts the final segment of a filepath URI, percent-decoded as UTF-8.
   *
   * <p>Unlike {@code path.getFileName().toString()}, which decodes the raw filesystem bytes using
   * {@code sun.jnu.encoding} and therefore silently substitutes U+FFFD for every non-ASCII byte
   * under a non-UTF-8 process locale, this is charset-independent: the URI produced by {@link
   * #encode(Path)} already carries the bytes verbatim.
   */
  public static String filenameOf(String filepathUri) {
    var pathComponent = stripTrailingSeparator(decodedPathComponentOf(filepathUri));
    var filename = pathComponent.substring(pathComponent.lastIndexOf(SEPARATOR) + 1);

    if (filename.isEmpty()) {
      throw new IllegalArgumentException("Filepath URI has no final segment: " + filepathUri);
    }

    return filename;
  }

  private static String decodedPathComponentOf(String filepathUri) {
    try {
      var uri = URI.create(filepathUri);
      if (uri.getScheme() != null && uri.getPath() != null) {
        return uri.getPath();
      }
    } catch (IllegalArgumentException _) {
      // fall through to raw path interpretation
    }
    return filepathUri;
  }

  private static String stripTrailingSeparator(String pathComponent) {
    if (pathComponent.length() > 1
        && pathComponent.charAt(pathComponent.length() - 1) == SEPARATOR) {
      return pathComponent.substring(0, pathComponent.length() - 1);
    }
    return pathComponent;
  }

  public static Path decode(String filepathUri) {
    return decode(FileSystems.getDefault(), filepathUri);
  }

  public static Path decode(FileSystem fileSystem, String filepathUri) {
    try {
      var uri = URI.create(filepathUri);
      if (uri.getScheme() != null) {
        return decodeUri(fileSystem, uri);
      }
    } catch (IllegalArgumentException | FileSystemNotFoundException _) {
      // fall through to raw path interpretation
    }
    return fileSystem.getPath(filepathUri);
  }

  private static Path decodeUri(FileSystem fileSystem, URI uri) {
    try {
      return fileSystem.provider().getPath(uri);
    } catch (UnsupportedOperationException _) {
      if ("file".equals(uri.getScheme())) {
        return fileSystem.getPath(uri.getPath());
      }
      return Path.of(uri);
    }
  }
}
