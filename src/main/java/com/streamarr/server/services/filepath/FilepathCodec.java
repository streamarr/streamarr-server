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
import java.nio.file.spi.FileSystemProvider;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Encodes paths as filesystem-provider URIs. URI text decoding is strict UTF-8; decoding to {@link
 * Path} preserves filesystem bytes. See ADR 0012.
 */
public final class FilepathCodec {

  private static final char SEPARATOR = '/';

  private FilepathCodec() {}

  public static String encode(Path path) {
    return path.toAbsolutePath().toUri().toString();
  }

  public static Path decode(String filepathUri) {
    return decode(FileSystems.getDefault(), filepathUri);
  }

  public static Path decode(FileSystem fileSystem, String filepathUri) {
    return decodeUri(fileSystem, parse(filepathUri), filepathUri);
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
    return decodeUtf8Path(parse(filepathUri), filepathUri);
  }

  private static URI parse(String filepathUri) {
    URI uri;

    try {
      uri = URI.create(filepathUri);
    } catch (IllegalArgumentException exception) {
      throw invalidFilepathUri(filepathUri, exception);
    }

    validateFileUriStructure(uri, filepathUri);

    if (isSupportedFileSystemUri(uri)) {
      return uri;
    }

    throw invalidFilepathUri(filepathUri);
  }

  private static boolean hasFileScheme(String value) {
    return value.regionMatches(true, 0, "file:", 0, "file:".length());
  }

  private static boolean isSupportedFileSystemUri(URI uri) {
    return !uri.isOpaque()
        && FileSystemProvider.installedProviders().stream()
            .anyMatch(provider -> provider.getScheme().equalsIgnoreCase(uri.getScheme()));
  }

  private static void validateFileUriStructure(URI uri, String filepathUri) {
    if (!hasFileScheme(filepathUri)) {
      return;
    }

    if (isValidFileUriStructure(uri)) {
      return;
    }

    throw invalidFilepathUri(filepathUri);
  }

  private static boolean isValidFileUriStructure(URI uri) {
    return !uri.isOpaque()
        && uri.getRawAuthority() == null
        && uri.getQuery() == null
        && uri.getFragment() == null;
  }

  private static IllegalArgumentException invalidFilepathUri(String filepathUri) {
    return new IllegalArgumentException("Invalid filepath URI: " + filepathUri);
  }

  private static IllegalArgumentException invalidFilepathUri(String filepathUri, Throwable cause) {
    return new IllegalArgumentException("Invalid filepath URI: " + filepathUri, cause);
  }

  private static String decodeUtf8Path(URI uri, String filepathUri) {
    var rawPath = uri.getRawPath();
    var decoded = new StringBuilder(rawPath.length());
    var index = 0;

    try {
      while (index < rawPath.length()) {
        index = appendNextDecodedChunk(rawPath, index, decoded);
      }
    } catch (CharacterCodingException exception) {
      throw invalidFilepathUri(filepathUri, exception);
    }

    return decoded.toString();
  }

  private static int appendNextDecodedChunk(String rawPath, int startIndex, StringBuilder decoded)
      throws CharacterCodingException {
    var percentIndex = rawPath.indexOf('%', startIndex);

    if (percentIndex < 0) {
      decoded.append(rawPath, startIndex, rawPath.length());
      return rawPath.length();
    }

    decoded.append(rawPath, startIndex, percentIndex);
    return appendPercentEncodedRun(rawPath, percentIndex, decoded);
  }

  private static int appendPercentEncodedRun(String rawPath, int startIndex, StringBuilder decoded)
      throws CharacterCodingException {
    var bytes = new byte[(rawPath.length() - startIndex) / 3];
    var byteCount = 0;
    var index = startIndex;

    for (; isPercentEncodedByteAt(rawPath, index); index += 3) {
      bytes[byteCount++] = decodePercentEncodedByte(rawPath, index);
    }

    var decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    decoded.append(decoder.decode(ByteBuffer.wrap(bytes, 0, byteCount)));
    return index;
  }

  private static boolean isPercentEncodedByteAt(String rawPath, int index) {
    return index + 2 < rawPath.length() && rawPath.charAt(index) == '%';
  }

  private static byte decodePercentEncodedByte(String rawPath, int index) {
    return (byte) Integer.parseInt(rawPath, index + 1, index + 3, 16);
  }

  private static Path decodeUri(FileSystem fileSystem, URI uri, String filepathUri) {
    try {
      if (fileSystem.provider().getScheme().equalsIgnoreCase(uri.getScheme())) {
        return pathFromProvider(fileSystem, uri);
      }

      if ("file".equalsIgnoreCase(uri.getScheme())) {
        return fileSystem.getPath(uri.getPath());
      }

      return Path.of(uri);
    } catch (FileSystemNotFoundException | IllegalArgumentException exception) {
      throw invalidFilepathUri(filepathUri, exception);
    }
  }

  private static Path pathFromProvider(FileSystem fileSystem, URI uri) {
    try {
      return fileSystem.provider().getPath(uri);
    } catch (UnsupportedOperationException _) {
      return Path.of(uri);
    }
  }
}
