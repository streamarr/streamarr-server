package com.streamarr.server.services.library;

import static com.streamarr.server.support.NonUtf8LocaleProbeSupport.firstRegularFileUnder;
import static com.streamarr.server.support.NonUtf8LocaleProbeSupport.report;

import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.parsers.show.SeasonPathMetadataParser;
import com.streamarr.server.services.parsers.show.SeriesFolderNameParser;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Reports how a JVM reads a filename whose bytes on disk are UTF-8, once through {@link Path} and
 * once through {@link FilepathCodec}.
 *
 * <p>Runs inside the container started by {@code NonUtf8LocaleFilenameIT} with only the compiled
 * classes on the classpath, so it must not reference Spring, JUnit, or any third-party library.
 */
public final class NonUtf8LocaleFilenameProbe {

  private static final SeasonPathMetadataParser SEASON_PARSER = new SeasonPathMetadataParser();
  private static final SeriesFolderNameParser SERIES_FOLDER_PARSER = new SeriesFolderNameParser();

  private NonUtf8LocaleFilenameProbe() {}

  public static void main(String[] args) throws IOException {
    var file = firstRegularFileUnder(Path.of(args[0]));
    var uri = FilepathCodec.encode(file);

    var pathParentName = file.getParent().getFileName().toString();
    var pathGrandparentName = file.getParent().getParent().getFileName().toString();
    var codecParentName = FilepathCodec.parentNameOf(uri).orElseThrow();
    var codecGrandparentName = FilepathCodec.grandparentNameOf(uri).orElseThrow();

    report("sun.jnu.encoding", System.getProperty("sun.jnu.encoding"));
    report("path.filename", file.getFileName().toString());
    report("path.parentName", pathParentName);
    report("path.grandparentName", pathGrandparentName);
    report("path.toString", file.toString());
    report("codec.uri", uri);
    report("codec.filename", FilepathCodec.filenameOf(uri));
    report("codec.parentName", codecParentName);
    report("codec.grandparentName", codecGrandparentName);
    report("codec.path", FilepathCodec.pathOf(uri));
    report("season.fromPathName", parseSeason(pathParentName));
    report("season.fromCodecName", parseSeason(codecParentName));
    report("seriesTitle.fromPathName", SERIES_FOLDER_PARSER.parse(pathGrandparentName).title());
    report("seriesTitle.fromCodecName", SERIES_FOLDER_PARSER.parse(codecGrandparentName).title());
  }

  private static String parseSeason(String directoryName) {
    var result = SEASON_PARSER.parse(directoryName).orElseThrow();
    var seasonNumber = "none";

    if (result.seasonNumber().isPresent()) {
      seasonNumber = String.valueOf(result.seasonNumber().getAsInt());
    }

    return seasonNumber + "/" + result.isSeasonFolder();
  }
}
