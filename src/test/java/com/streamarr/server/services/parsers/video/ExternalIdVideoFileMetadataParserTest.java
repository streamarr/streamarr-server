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
class ExternalIdVideoFileMetadataParserTest {

  private final ExternalIdVideoFileMetadataParser externalIdVideoFileMetadataParser =
      new ExternalIdVideoFileMetadataParser();

  @Nested
  @DisplayName("Should successfully extract both id and source from filename")
  class SuccessfulExternalIdAndSourceExtractionTests {

    record TestCase(ExternalSourceType source, String id, String filename) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
              new TestCase(
                  ExternalSourceType.IMDB,
                  "tt13581194",
                  "Slate Garden (2022) [imdb-tt13581194][WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv"),
              new TestCase(
                  ExternalSourceType.IMDB,
                  "tt13581194",
                  "Slate Garden (2022) [IMDB-tt13581194][WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv"),
              new TestCase(
                  ExternalSourceType.IMDB,
                  "tt13581194",
                  "Slate Garden (2022) [imdb tt13581194][WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv"),
              new TestCase(
                  ExternalSourceType.IMDB,
                  "tt13581194",
                  "Slate Garden (2022) [IMDB tt13581194][WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv"),
              new TestCase(
                  ExternalSourceType.IMDB,
                  "tt13581194",
                  "Slate Garden (2022) {imdb-tt13581194}[WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv"),
              new TestCase(
                  ExternalSourceType.IMDB,
                  "tt13581194",
                  "Slate Garden (2022) {IMDB-tt13581194}[WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv"),
              new TestCase(
                  ExternalSourceType.IMDB,
                  "tt13581194",
                  "Slate Garden (2022) {imdb tt13581194}[WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv"),
              new TestCase(
                  ExternalSourceType.IMDB,
                  "tt13581194",
                  "Slate Garden (2022) {IMDB tt13581194}[WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv"),
              new TestCase(
                  ExternalSourceType.TMDB,
                  "815339",
                  "Glint (2022) [tmdb-815339][WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv"),
              new TestCase(
                  ExternalSourceType.TMDB,
                  "815339",
                  "Glint (2022) [tmdb-815339][WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv"),
              new TestCase(
                  ExternalSourceType.TMDB,
                  "815339",
                  "Glint (2022) [tmdb 815339][WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv"),
              new TestCase(
                  ExternalSourceType.TMDB,
                  "815339",
                  "Glint (2022) [tmdb 815339][WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv"),
              new TestCase(
                  ExternalSourceType.TMDB,
                  "815339",
                  "Glint (2022) {tmdb-815339}[WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv"),
              new TestCase(
                  ExternalSourceType.TMDB,
                  "815339",
                  "Glint (2022) {tmdb-815339}[WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv"),
              new TestCase(
                  ExternalSourceType.TMDB,
                  "815339",
                  "Glint (2022) {tmdb 815339}[WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv"),
              new TestCase(
                  ExternalSourceType.TMDB,
                  "815339",
                  "Glint (2022) {tmdb 815339}[WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv"),
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
  @DisplayName("Should resolve a single external ID when tags collide or delimiters are irregular")
  class AdversarialExternalIdExtractionTests {

    record TestCase(String testName, ExternalSourceType source, String id, String filename) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
              new TestCase(
                  "when delimiter pair is mismatched",
                  ExternalSourceType.TMDB,
                  "507329",
                  "Velvet Antenna {tmdb-507329).mkv"),
              new TestCase(
                  "when an unknown key shares a known prefix",
                  ExternalSourceType.IMDB,
                  "tt7654321",
                  "Velvet Antenna [imdbid1=tt0000001][imdbid=tt7654321].mkv"),
              new TestCase(
                  "when multiple sources are present the first tag wins",
                  ExternalSourceType.IMDB,
                  "tt4839021",
                  "Velvet Antenna [imdb-tt4839021][tmdb-507329].mkv"),
              new TestCase(
                  "when tag is nested in doubled brackets",
                  ExternalSourceType.TMDB,
                  "507329",
                  "Velvet Antenna [[tmdb-507329]].mkv"))
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.testName(),
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
  class ShouldFailToExtractIdAndSource {

    record TestCase(String testName, String filename) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
              new TestCase(
                  "when given title without external ID tag",
                  "Slate Garden (2022) imdb tt13581194 [WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv"),
              new TestCase("when given null input", null),
              new TestCase("when given empty input", ""),
              new TestCase("when given blank input", " "),
              new TestCase(
                  "when external ID tag has an empty value", "Velvet Antenna [tmdbid=].mkv"),
              new TestCase(
                  "when external ID tag is never closed", "Velvet Antenna [tmdb-507329.mkv"))
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
