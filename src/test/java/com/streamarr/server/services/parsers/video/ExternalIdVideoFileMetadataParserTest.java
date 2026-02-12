package com.streamarr.server.services.parsers.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.ExternalSourceType;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

@Tag("UnitTest")
@DisplayName("External Id Video File Metadata Parsing Tests")
public class ExternalIdVideoFileMetadataParserTest {

  private final ExternalIdVideoFileMetadataParser externalIdVideoFileMetadataParser =
      new ExternalIdVideoFileMetadataParser();

  @Nested
  @DisplayName("Should successfully extract both id and source from filename")
  public class SuccessfulExternalIdAndSourceExtractionTests {

    record TestCase(ExternalSourceType source, String id, String filename) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
              new TestCase(
                  ExternalSourceType.IMDB,
                  "tt13327038",
                  "Do Revenge (2022) [imdb-tt13327038][WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
              new TestCase(
                  ExternalSourceType.IMDB,
                  "tt13327038",
                  "Do Revenge (2022) [IMDB-tt13327038][WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
              new TestCase(
                  ExternalSourceType.IMDB,
                  "tt13327038",
                  "Do Revenge (2022) [imdb tt13327038][WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
              new TestCase(
                  ExternalSourceType.IMDB,
                  "tt13327038",
                  "Do Revenge (2022) [IMDB tt13327038][WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
              new TestCase(
                  ExternalSourceType.IMDB,
                  "tt13327038",
                  "Do Revenge (2022) {imdb-tt13327038}[WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
              new TestCase(
                  ExternalSourceType.IMDB,
                  "tt13327038",
                  "Do Revenge (2022) {IMDB-tt13327038}[WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
              new TestCase(
                  ExternalSourceType.IMDB,
                  "tt13327038",
                  "Do Revenge (2022) {imdb tt13327038}[WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
              new TestCase(
                  ExternalSourceType.IMDB,
                  "tt13327038",
                  "Do Revenge (2022) {IMDB tt13327038}[WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
              new TestCase(
                  ExternalSourceType.TMDB,
                  "762504",
                  "Nope (2022) [tmdb-762504][WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv"),
              new TestCase(
                  ExternalSourceType.TMDB,
                  "762504",
                  "Nope (2022) [tmdb-762504][WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv"),
              new TestCase(
                  ExternalSourceType.TMDB,
                  "762504",
                  "Nope (2022) [tmdb 762504][WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv"),
              new TestCase(
                  ExternalSourceType.TMDB,
                  "762504",
                  "Nope (2022) [tmdb 762504][WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv"),
              new TestCase(
                  ExternalSourceType.TMDB,
                  "762504",
                  "Nope (2022) {tmdb-762504}[WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv"),
              new TestCase(
                  ExternalSourceType.TMDB,
                  "762504",
                  "Nope (2022) {tmdb-762504}[WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv"),
              new TestCase(
                  ExternalSourceType.TMDB,
                  "762504",
                  "Nope (2022) {tmdb 762504}[WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv"),
              new TestCase(
                  ExternalSourceType.TMDB,
                  "762504",
                  "Nope (2022) {tmdb 762504}[WEBDL-1080p][EAC3 5.1][h264]-EVO.mkv"),
              new TestCase(
                  ExternalSourceType.IMDB, "tt1234567", "Movie (2022) (imdb-tt1234567).mkv"),
              new TestCase(
                  ExternalSourceType.IMDB, "tt1234567", "Movie (2022) [imdb=tt1234567].mkv"),
              new TestCase(ExternalSourceType.TVDB, "12345", "Movie (2022) [tvdb-12345].mkv"),
              new TestCase(
                  ExternalSourceType.IMDB, "tt1234567", "Movie (2022) [imdbid-tt1234567].mkv"),
              new TestCase(ExternalSourceType.TMDB, "12345", "Movie (2022) [tmdbid=12345].mkv"),
              new TestCase(ExternalSourceType.TVDB, "67890", "Movie (2022) {tvdbid=67890}.mkv"))
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.filename(),
                      () -> {
                        var result = externalIdVideoFileMetadataParser.parse(testCase.filename());

                        assertThat(result.orElseThrow().externalSource())
                            .isEqualTo(testCase.source());
                        assertThat(result.orElseThrow().externalId()).isEqualTo(testCase.id());
                      }));
    }
  }

  @Nested
  @DisplayName("Should fail to extract both id and source from filename")
  public class ShouldFailToExtractIdAndSource {

    record TestCase(String testName, String filename) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
              new TestCase(
                  "when given title without external ID tag",
                  "Do Revenge (2022) imdb tt13327038 [WEBDL-1080p][EAC3 Atmos 5.1][x264]-EVO.mkv"),
              new TestCase("when given null input", null),
              new TestCase("when given empty input", ""),
              new TestCase("when given blank input", " "))
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.testName(),
                      () -> {
                        var result = externalIdVideoFileMetadataParser.parse(testCase.filename());

                        assertThat(result).isEmpty();
                      }));
    }
  }
}
