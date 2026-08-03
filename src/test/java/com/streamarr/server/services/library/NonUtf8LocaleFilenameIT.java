package com.streamarr.server.services.library;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

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
 * <p>The bug needs a Linux JVM whose {@code sun.jnu.encoding} resolves to ASCII, which macOS cannot
 * produce: {@code -Dsun.jnu.encoding} is ignored on the command line there, and {@code
 * LC_ALL=POSIX} yields a single {@code ?} per character from the macOS filesystem layer instead of
 * the two U+FFFD per accented character Linux produces. So the scenario runs inside a JDK 25 Linux
 * container.
 */
@Tag("IntegrationTest")
@DisplayName("Non UTF-8 Locale Filename Integration Tests")
class NonUtf8LocaleFilenameIT {

  private static final DockerImageName JDK_IMAGE = DockerImageName.parse("eclipse-temurin:25-jdk");

  private static final String MOVIE_ROOT = "/media/movies";
  private static final String MOVIE_FOLDER = "Déjà Vu (2006)";
  private static final String MOVIE_FILENAME = "Déjà Vu (2006) - [BLURAY-1080p][DTS 5.1].mkv";

  // Path.toString() emits one U+FFFD per byte the platform charset cannot map, so an accented
  // character read back from UTF-8 bytes becomes two of them.
  private static final String REPLACEMENT_CHAR = Character.toString(0xFFFD);
  private static final String MANGLED_MOVIE_FILENAME =
      "D"
          + REPLACEMENT_CHAR.repeat(2)
          + "j"
          + REPLACEMENT_CHAR.repeat(2)
          + " Vu (2006) - [BLURAY-1080p][DTS 5.1].mkv";

  private static GenericContainer<?> container;
  private static Map<String, String> movieReport;

  @BeforeAll
  static void probeFilenamesUnderAnAsciiLocale() throws Exception {
    container =
        new GenericContainer<>(JDK_IMAGE)
            .withCopyFileToContainer(
                MountableFile.forHostPath(Path.of("target", "classes")), "/app/classes")
            .withCopyFileToContainer(
                MountableFile.forHostPath(Path.of("target", "test-classes")), "/app/test-classes")
            .withEnv("LC_ALL", "POSIX")
            .withEnv("LANG", "POSIX")
            .withCommand("sleep", "infinity");
    container.start();

    createFile(MOVIE_ROOT + "/" + MOVIE_FOLDER + "/" + MOVIE_FILENAME);

    movieReport = probe(MOVIE_ROOT);
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
    assertThat(movieReport).containsEntry("sun.jnu.encoding", "ANSI_X3.4-1968");
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
    assertThat(movieReport.get("codec.uri"))
        .isEqualTo(
            "file:///media/movies/D%C3%A9j%C3%A0%20Vu%20(2006)/"
                + "D%C3%A9j%C3%A0%20Vu%20(2006)%20-%20%5BBLURAY-1080p%5D%5BDTS%205.1%5D.mkv");
  }

  @Test
  @DisplayName("Should return the on-disk filename when deriving it from the filepath URI")
  void shouldReturnOnDiskFilenameWhenDerivingItFromFilepathUri() {
    assertThat(movieReport.get("codec.filename"))
        .doesNotContain(REPLACEMENT_CHAR)
        .isEqualTo(MOVIE_FILENAME);
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
}
