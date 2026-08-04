package com.streamarr.server.services.filepath;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Encodes paths as file URIs while continuing to read legacy raw paths. URI text decoding is strict
 * UTF-8; decoding to {@link Path} preserves filesystem bytes. See ADR 0012.
 */
public final class FilepathCodec {

  private static final char SEPARATOR = '/';

  private FilepathCodec() {}

  public static String encode(Path path) {
    return path.toAbsolutePath().toUri().toString();
  }

  public static String filenameOf(String filepathUri) {
    return nameAbove(filepathUri, 0)
        .orElseThrow(
            () ->
                new IllegalArgumentException("Filepath URI has no final segment: " + filepathUri));
  }

  public static Optional<String> parentNameOf(String filepathUri) {
    return nameAbove(filepathUri, 1);
  }

  public static Optional<String> grandparentNameOf(String filepathUri) {
    return nameAbove(filepathUri, 2);
  }

  public static String pathOf(String filepathUri) {
    return decodedPathComponentOf(filepathUri);
  }

  private static Optional<String> nameAbove(String filepathUri, int directoriesAboveTheFile) {
    var segments = segmentsOf(filepathUri);
    var index = segments.size() - 1 - directoriesAboveTheFile;

    if (index < 0) {
      return Optional.empty();
    }

    return Optional.of(segments.get(index));
  }

  private static List<String> segmentsOf(String filepathUri) {
    return Arrays.stream(pathOf(filepathUri).split(String.valueOf(SEPARATOR)))
        .filter(segment -> !segment.isEmpty())
        .toList();
  }

  private static String decodedPathComponentOf(String filepathUri) {
    try {
      var uri = URI.create(filepathUri);
      validateFileUriStructure(uri, filepathUri);
      if (uri.getScheme() != null && uri.getPath() != null) {
        return decodeUtf8Path(uri, filepathUri);
      }
    } catch (IllegalArgumentException exception) {
      if (hasFileScheme(filepathUri)) {
        throw new IllegalArgumentException("Invalid filepath URI: " + filepathUri, exception);
      }
    }
    return filepathUri;
  }

  private static boolean hasFileScheme(String value) {
    return value.regionMatches(true, 0, "file:", 0, "file:".length());
  }

  private static void validateFileUriStructure(URI uri, String filepathUri) {
    if (!hasFileScheme(filepathUri)) {
      return;
    }

    if (!uri.isOpaque() && uri.getQuery() == null && uri.getFragment() == null) {
      return;
    }

    throw invalidFilepathUri(filepathUri);
  }

  private static IllegalArgumentException invalidFilepathUri(String filepathUri) {
    return new IllegalArgumentException("Invalid filepath URI: " + filepathUri);
  }

  private static String decodeUtf8Path(URI uri, String filepathUri) {
    var rawPath = uri.getRawPath();
    var decoded = new StringBuilder(rawPath.length());
    var index = 0;

    try {
      while (index < rawPath.length()) {
        var percentIndex = rawPath.indexOf('%', index);
        if (percentIndex < 0) {
          decoded.append(rawPath, index, rawPath.length());
          break;
        }

        decoded.append(rawPath, index, percentIndex);
        var bytes = new byte[(rawPath.length() - percentIndex) / 3];
        var byteCount = 0;
        index = percentIndex;

        while (index + 2 < rawPath.length() && rawPath.charAt(index) == '%') {
          bytes[byteCount++] = (byte) Integer.parseInt(rawPath, index + 1, index + 3, 16);
          index += 3;
        }

        var decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        decoded.append(decoder.decode(ByteBuffer.wrap(bytes, 0, byteCount)));
      }
    } catch (CharacterCodingException | NumberFormatException exception) {
      throw new IllegalArgumentException("Invalid filepath URI: " + filepathUri, exception);
    }

    return decoded.toString();
  }

  public static Path decode(String filepathUri) {
    return decode(FileSystems.getDefault(), filepathUri);
  }

  public static Path decode(FileSystem fileSystem, String filepathUri) {
    URI uri;

    try {
      uri = URI.create(filepathUri);
    } catch (IllegalArgumentException exception) {
      return legacyPathOrThrow(fileSystem, filepathUri, exception);
    }

    if (uri.getScheme() == null) {
      // ADR 0012 keeps raw paths readable for pre-migration rows.
      return fileSystem.getPath(filepathUri);
    }

    try {
      validateFileUriStructure(uri, filepathUri);
      return decodeUri(fileSystem, uri);
    } catch (FileSystemNotFoundException _) {
      return fileSystem.getPath(filepathUri);
    } catch (IllegalArgumentException exception) {
      return legacyPathOrThrow(fileSystem, filepathUri, exception);
    }
  }

  private static Path legacyPathOrThrow(
      FileSystem fileSystem, String filepathUri, IllegalArgumentException exception) {
    if (!hasFileScheme(filepathUri)) {
      return fileSystem.getPath(filepathUri);
    }

    throw new IllegalArgumentException("Invalid filepath URI: " + filepathUri, exception);
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
