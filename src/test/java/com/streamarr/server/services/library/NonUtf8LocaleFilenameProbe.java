package com.streamarr.server.services.library;

import com.streamarr.server.services.parsers.show.SeasonPathMetadataParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Reports how a JVM reads a filename whose bytes on disk are UTF-8, once through {@link Path} and
 * once through {@link FilepathCodec}.
 *
 * <p>Runs inside the container started by {@code NonUtf8LocaleFilenameIT} with only the compiled
 * classes on the classpath, so it must not reference Spring, JUnit, or any third-party library.
 *
 * <p>Every value is printed as base64 of its UTF-8 bytes. Under an ASCII locale {@code System.out}
 * encodes with {@code sun.jnu.encoding}, which would turn the U+FFFD characters this probe exists
 * to observe into plain question marks before the test could see them.
 */
public final class NonUtf8LocaleFilenameProbe {

  private static final SeasonPathMetadataParser SEASON_PARSER = new SeasonPathMetadataParser();

  private NonUtf8LocaleFilenameProbe() {}

  public static void main(String[] args) throws IOException {
    var file = firstRegularFileUnder(Path.of(args[0]));
    var uri = FilepathCodec.encode(file);

    var pathParentName = file.getParent().getFileName().toString();
    var codecParentName = FilepathCodec.parentNameOf(uri).orElseThrow();

    report("sun.jnu.encoding", System.getProperty("sun.jnu.encoding"));
    report("path.filename", file.getFileName().toString());
    report("path.parentName", pathParentName);
    report("path.grandparentName", file.getParent().getParent().getFileName().toString());
    report("path.toString", file.toString());
    report("codec.uri", uri);
    report("codec.filename", FilepathCodec.filenameOf(uri));
    report("codec.parentName", codecParentName);
    report("codec.grandparentName", FilepathCodec.grandparentNameOf(uri).orElseThrow());
    report("codec.path", FilepathCodec.pathOf(uri));
    report("season.fromPathName", parseSeason(pathParentName));
    report("season.fromCodecName", parseSeason(codecParentName));
  }

  private static String parseSeason(String directoryName) {
    var result = SEASON_PARSER.parse(directoryName).orElseThrow();
    var seasonNumber = "none";

    if (result.seasonNumber().isPresent()) {
      seasonNumber = String.valueOf(result.seasonNumber().getAsInt());
    }

    return seasonNumber + "/" + result.isSeasonFolder();
  }

  private static Path firstRegularFileUnder(Path root) throws IOException {
    try (var entries = Files.walk(root)) {
      return entries.filter(Files::isRegularFile).findFirst().orElseThrow();
    }
  }

  private static void report(String key, String value) {
    System.out.println(
        key + "=" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
  }
}
