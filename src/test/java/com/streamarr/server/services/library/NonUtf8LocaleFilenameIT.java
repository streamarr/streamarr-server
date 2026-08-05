package com.streamarr.server.services.library;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Reproduces the locale-dependent filename corruption the shipped container hits, and pins the
 * decode that survives it.
 *
 * <p>The bug needs a Linux JVM whose {@code sun.jnu.encoding} resolves to ASCII. On macOS, {@code
 * -Dsun.jnu.encoding} is ignored and {@code LC_ALL=POSIX} leaves filesystem decoding as UTF-8; only
 * console output switches to US-ASCII. The scenario therefore runs inside a JDK 25 Linux container
 * and is tagged as an integration test because it launches and controls that external process.
 */
@Tag("IntegrationTest")
@DisplayName("Non UTF-8 Locale Filename Integration Tests")
class NonUtf8LocaleFilenameIT {

  private static final DockerImageName JDK_IMAGE =
      DockerImageName.parse(
          "eclipse-temurin:25-jdk@sha256:12e44624adee6808a36d962717e1656e0afeeeff5a100f9cb00e0136513558f0");

  private static final String MOVIE_ROOT = "/media/movies";
  private static final String MOVIE_FOLDER = "Café Meridian (2006)";
  private static final String MOVIE_FILENAME = "Café Meridian (2006) - [BLURAY-1080p][DTS 5.1].mkv";

  private static final String SERIES_ROOT = "/media/series";
  private static final String SERIES_FOLDER = "Lumière Harbor (2001)";
  private static final String SEASON_FOLDER = "Sæson 3";
  private static final String EPISODE_FILENAME = "Lumiere.Harbor.S03E05.mkv";

  // Path.toString() emits one U+FFFD per byte the platform charset cannot map, so an accented
  // character read back from UTF-8 bytes becomes two of them.
  private static final String REPLACEMENT_CHAR = Character.toString(0xFFFD);
  private static final String MANGLED_MOVIE_FILENAME =
      "Caf" + REPLACEMENT_CHAR.repeat(2) + " Meridian (2006) - [BLURAY-1080p][DTS 5.1].mkv";

  private static final String MANGLED_SEASON_FOLDER = "S" + REPLACEMENT_CHAR.repeat(2) + "son 3";

  private static GenericContainer<?> container;
  private static Map<String, String> movieReport;
  private static Map<String, String> seriesReport;

  @BeforeAll
  static void probeFilenamesUnderAnAsciiLocale() throws Exception {
    container =
        new GenericContainer<>(JDK_IMAGE)
            .withCopyFileToContainer(
                MountableFile.forHostPath(classesDirectoryOf(LibraryManagementService.class)),
                "/app/classes")
            .withCopyFileToContainer(
                MountableFile.forHostPath(classesDirectoryOf(NonUtf8LocaleFilenameIT.class)),
                "/app/test-classes")
            .withEnv("LC_ALL", "POSIX")
            .withEnv("LANG", "POSIX")
            .withCommand("sleep", "infinity");
    container.start();

    createFile(MOVIE_ROOT + "/" + MOVIE_FOLDER + "/" + MOVIE_FILENAME);
    createFile(SERIES_ROOT + "/" + SERIES_FOLDER + "/" + SEASON_FOLDER + "/" + EPISODE_FILENAME);

    movieReport = probe(MOVIE_ROOT);
    seriesReport = probe(SERIES_ROOT);
  }

  @AfterAll
  static void stopContainer() {
    if (container != null) {
      container.stop();
    }
  }

  @Test
  @DisplayName("Should resolve sun.jnu.encoding to ASCII when the container locale is POSIX")
  void shouldResolveSunJnuEncodingToAsciiWhenContainerLocaleIsPosix() {
    assertThat(Charset.forName(movieReport.get("sun.jnu.encoding")))
        .isEqualTo(StandardCharsets.US_ASCII);
  }

  @Test
  @DisplayName("Should replace every accented byte with U+FFFD when filename is read through Path")
  void shouldReplaceEveryAccentedByteWithReplacementCharWhenFilenameIsReadThroughPath() {
    assertThat(movieReport.get("path.filename"))
        .contains(REPLACEMENT_CHAR)
        .isEqualTo(MANGLED_MOVIE_FILENAME);
  }

  @Test
  @DisplayName("Should percent-encode the raw filesystem bytes when encoding a path to a URI")
  void shouldPercentEncodeRawFilesystemBytesWhenEncodingPathToUri() {
    assertThat(movieReport)
        .containsEntry(
            "codec.uri",
            "file:///media/movies/Caf%C3%A9%20Meridian%20(2006)/"
                + "Caf%C3%A9%20Meridian%20(2006)%20-%20%5BBLURAY-1080p%5D%5BDTS%205.1%5D.mkv");
  }

  @Test
  @DisplayName("Should return the on-disk filename when deriving it from the filepath URI")
  void shouldReturnOnDiskFilenameWhenDerivingItFromFilepathUri() {
    assertThat(movieReport.get("codec.filename"))
        .doesNotContain(REPLACEMENT_CHAR)
        .isEqualTo(MOVIE_FILENAME);
  }

  @Test
  @DisplayName("Should name the movie folder from the URI when Path mangles it")
  void shouldNameMovieFolderFromUriWhenPathManglesIt() {
    assertThat(movieReport.get("path.parentName")).contains(REPLACEMENT_CHAR);
    assertThat(movieReport).containsEntry("codec.parentName", MOVIE_FOLDER);
  }

  @Test
  @DisplayName("Should mangle directory names above the file when they are read through Path")
  void shouldMangleDirectoryNamesAboveFileWhenTheyAreReadThroughPath() {
    assertThat(seriesReport).containsEntry("path.parentName", MANGLED_SEASON_FOLDER);
    assertThat(seriesReport.get("path.grandparentName")).contains(REPLACEMENT_CHAR);
    assertThat(seriesReport.get("path.toString")).contains(REPLACEMENT_CHAR);
  }

  @Test
  @DisplayName("Should name the directories above the file when deriving them from the URI")
  void shouldNameDirectoriesAboveFileWhenDerivingThemFromUri() {
    assertThat(seriesReport)
        .containsEntry("codec.parentName", SEASON_FOLDER)
        .containsEntry("codec.grandparentName", SERIES_FOLDER)
        .containsEntry(
            "codec.path",
            SERIES_ROOT + "/" + SERIES_FOLDER + "/" + SEASON_FOLDER + "/" + EPISODE_FILENAME);
  }

  @Test
  @DisplayName("Should read no season number when the season folder name came through Path")
  void shouldReadNoSeasonNumberWhenSeasonFolderNameCameThroughPath() {
    assertThat(seriesReport).containsEntry("season.fromPathName", "none/false");
  }

  @Test
  @DisplayName("Should read the season number when the season folder name came from the URI")
  void shouldReadSeasonNumberWhenSeasonFolderNameCameFromUri() {
    assertThat(seriesReport).containsEntry("season.fromCodecName", "3/true");
  }

  @Test
  @DisplayName("Should hand out a mangled series title when the folder name came through Path")
  void shouldHandOutMangledSeriesTitleWhenFolderNameCameThroughPath() {
    assertThat(seriesReport.get("seriesTitle.fromPathName")).contains(REPLACEMENT_CHAR);
  }

  @Test
  @DisplayName("Should hand out the real series title when the folder name came from the URI")
  void shouldHandOutRealSeriesTitleWhenFolderNameCameFromUri() {
    assertThat(seriesReport).containsEntry("seriesTitle.fromCodecName", "Lumière Harbor");
  }

  /**
   * The path travels as base64 so the shell writes exactly these UTF-8 bytes, whatever charset the
   * docker exec transport applies to the command itself.
   */
  private static void createFile(String absolutePath) throws Exception {
    var command =
        "p=$(printf %s '"
            + base64(absolutePath)
            + "' | base64 -d) && mkdir -p \"$(dirname \"$p\")\" && : > \"$p\"";

    var result = container.execInContainer("sh", "-c", command);

    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
  }

  private static Map<String, String> probe(String root) throws Exception {
    var result =
        container.execInContainer(
            "java",
            "-cp",
            "/app/classes:/app/test-classes",
            NonUtf8LocaleFilenameProbe.class.getName(),
            root);

    assertThat(result.getExitCode()).as(result.getStderr()).isZero();

    return decodeReport(result.getStdout());
  }

  private static Map<String, String> decodeReport(String stdout) {
    var report = new HashMap<String, String>();

    stdout
        .lines()
        .filter(line -> line.contains("="))
        .forEach(
            line -> {
              var separator = line.indexOf('=');
              report.put(line.substring(0, separator), decodeBase64(line.substring(separator + 1)));
            });

    return report;
  }

  private static String base64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(UTF_8));
  }

  private static String decodeBase64(String value) {
    return new String(Base64.getDecoder().decode(value), UTF_8);
  }

  private static Path classesDirectoryOf(Class<?> type) throws URISyntaxException {
    return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
  }
}
