package com.streamarr.server.services.library;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Path;

public final class FilepathCodec {

  private FilepathCodec() {}

  public static String encode(Path path) {
    return path.toAbsolutePath().toUri().toString();
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
    } catch (IllegalArgumentException | FileSystemNotFoundException e) {
      // Not a valid URI or filesystem not found, fall through
    }
    return fileSystem.getPath(filepathUri);
  }

  private static Path decodeUri(FileSystem fileSystem, URI uri) {
    try {
      return fileSystem.provider().getPath(uri);
    } catch (UnsupportedOperationException e) {
      return Path.of(uri);
    }
  }
}
