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
 * Encodes filesystem paths for persistence and recovers paths or display text from those values.
 *
 * <p>New values are absolute {@code file:} URIs. Their percent-encoded path bytes are decoded
 * strictly as UTF-8 when requested as text; malformed file URIs and invalid UTF-8 text are
 * rejected. Path decoding preserves percent-encoded filesystem bytes. Scheme-less strings remain
 * supported as legacy persisted paths. See ADR 0012 for the storage contract.
 */
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
   * #encode(Path)} carries the filename as percent-encoded UTF-8. Invalid UTF-8 sequences are
   * rejected rather than replaced with U+FFFD.
   */
  public static String filenameOf(String filepathUri) {
    return nameAbove(filepathUri, 0)
        .orElseThrow(
            () ->
                new IllegalArgumentException("Filepath URI has no final segment: " + filepathUri));
  }

  /**
   * Name of the directory holding the file a filepath URI denotes, percent-decoded as UTF-8. Empty
   * when the file sits at the root.
   *
   * <p>Charset-independent for the same reason as {@link #filenameOf(String)}: {@code
   * path.getParent().getFileName().toString()} would decode the raw filesystem bytes with {@code
   * sun.jnu.encoding}.
   */
  public static Optional<String> parentNameOf(String filepathUri) {
    return nameAbove(filepathUri, 1);
  }

  /**
   * Name of the directory one level above {@link #parentNameOf(String)}, percent-decoded as UTF-8.
   * Empty when no directory sits that far above the file.
   */
  public static Optional<String> grandparentNameOf(String filepathUri) {
    return nameAbove(filepathUri, 2);
  }

  /**
   * The whole path a filepath URI denotes, percent-decoded as UTF-8, as text rather than a {@link
   * Path} whose {@code toString()} would run the raw bytes back through {@code sun.jnu.encoding}.
   */
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
      // Scheme-less legacy paths can contain characters that URI parsing rejects.
    }
    // Deliberate compatibility path for values persisted before ADR 0012.
    return filepathUri;
  }

  private static boolean hasFileScheme(String value) {
    return value.regionMatches(true, 0, "file:", 0, "file:".length());
  }

  private static void validateFileUriStructure(URI uri, String filepathUri) {
    if (hasFileScheme(filepathUri)
        && (uri.isOpaque() || uri.getQuery() != null || uri.getFragment() != null)) {
      throw invalidFilepathUri(filepathUri);
    }
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
    try {
      var uri = URI.create(filepathUri);
      if (uri.getScheme() != null) {
        if (hasFileScheme(filepathUri)) {
          validateFileUriStructure(uri, filepathUri);
        }
        return decodeUri(fileSystem, uri);
      }
    } catch (IllegalArgumentException exception) {
      if (hasFileScheme(filepathUri)) {
        throw new IllegalArgumentException("Invalid filepath URI: " + filepathUri, exception);
      }
      // Scheme-less legacy paths can contain characters that URI parsing rejects.
    } catch (FileSystemNotFoundException _) {
      // An unavailable non-file provider can still represent a legacy raw path.
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
