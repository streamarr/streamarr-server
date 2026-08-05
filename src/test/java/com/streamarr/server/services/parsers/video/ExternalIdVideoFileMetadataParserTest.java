package com.streamarr.server.services.parsers.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.ExternalSourceType;
import java.util.stream.Stream;
import lombok.Builder;
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
  @DisplayName("External ID and Source Extraction Tests")
  class SuccessfulExternalIdAndSourceExtractionTests {

    @Builder
    record TestCase(ExternalSourceType source, String id, String filename) {}

    private TestCase.TestCaseBuilder testCase() {
      return TestCase.builder();
    }

    @TestFactory
    @DisplayName("Should extract external ID and source when filename contains a supported tag")
    Stream<DynamicNode> shouldExtractExternalIdAndSourceWhenFilenameContainsSupportedTag() {
      return Stream.of(
              testCase()
                  .source(ExternalSourceType.IMDB)
                  .id("tt13581194")
                  .filename(
                      "Slate Garden (2022) [imdb-tt13581194][WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.IMDB)
                  .id("tt13581194")
                  .filename(
                      "Slate Garden (2022) [IMDB-tt13581194][WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.IMDB)
                  .id("tt13581194")
                  .filename(
                      "Slate Garden (2022) [imdb tt13581194][WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.IMDB)
                  .id("tt13581194")
                  .filename(
                      "Slate Garden (2022) [IMDB tt13581194][WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.IMDB)
                  .id("tt13581194")
                  .filename(
                      "Slate Garden (2022) {imdb-tt13581194}[WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.IMDB)
                  .id("tt13581194")
                  .filename(
                      "Slate Garden (2022) {IMDB-tt13581194}[WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.IMDB)
                  .id("tt13581194")
                  .filename(
                      "Slate Garden (2022) {imdb tt13581194}[WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.IMDB)
                  .id("tt13581194")
                  .filename(
                      "Slate Garden (2022) {IMDB tt13581194}[WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.TMDB)
                  .id("815339")
                  .filename("Glint (2022) [tmdb-815339][WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.TMDB)
                  .id("815339")
                  .filename("Glint (2022) [tmdb-815339][WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.TMDB)
                  .id("815339")
                  .filename("Glint (2022) [tmdb 815339][WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.TMDB)
                  .id("815339")
                  .filename("Glint (2022) [tmdb 815339][WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.TMDB)
                  .id("815339")
                  .filename("Glint (2022) {tmdb-815339}[WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.TMDB)
                  .id("815339")
                  .filename("Glint (2022) {tmdb-815339}[WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.TMDB)
                  .id("815339")
                  .filename("Glint (2022) {tmdb 815339}[WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.TMDB)
                  .id("815339")
                  .filename("Glint (2022) {tmdb 815339}[WEBDL-1080p][EAC3 5.1][h264]-KLV.mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.IMDB)
                  .id("tt1234567")
                  .filename("Movie (2022) (imdb-tt1234567).mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.IMDB)
                  .id("tt1234567")
                  .filename("Movie (2022) [imdb=tt1234567].mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.TVDB)
                  .id("12345")
                  .filename("Movie (2022) [tvdb-12345].mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.IMDB)
                  .id("tt1234567")
                  .filename("Movie (2022) [imdbid-tt1234567].mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.TMDB)
                  .id("12345")
                  .filename("Movie (2022) [tmdbid=12345].mkv")
                  .build(),
              testCase()
                  .source(ExternalSourceType.TVDB)
                  .id("67890")
                  .filename("Movie (2022) {tvdbid=67890}.mkv")
                  .build())
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
  @DisplayName("Adversarial External ID Extraction Tests")
  class AdversarialExternalIdExtractionTests {

    @Builder
    record TestCase(String testName, ExternalSourceType source, String id, String filename) {}

    private TestCase.TestCaseBuilder testCase() {
      return TestCase.builder();
    }

    @TestFactory
    @DisplayName(
        "Should resolve a single external ID when tags collide or delimiters are irregular")
    Stream<DynamicNode> shouldResolveSingleExternalIdWhenTagsCollideOrDelimitersAreIrregular() {
      return Stream.of(
              testCase()
                  .testName("when delimiter pair is mismatched")
                  .source(ExternalSourceType.TMDB)
                  .id("507329")
                  .filename("Velvet Antenna {tmdb-507329).mkv")
                  .build(),
              testCase()
                  .testName("when an unknown key shares a known prefix")
                  .source(ExternalSourceType.IMDB)
                  .id("tt7654321")
                  .filename("Velvet Antenna [imdbid1=tt0000001][imdbid=tt7654321].mkv")
                  .build(),
              testCase()
                  .testName("when multiple sources are present the first tag wins")
                  .source(ExternalSourceType.IMDB)
                  .id("tt4839021")
                  .filename("Velvet Antenna [imdb-tt4839021][tmdb-507329].mkv")
                  .build(),
              testCase()
                  .testName("when tag is nested in doubled brackets")
                  .source(ExternalSourceType.TMDB)
                  .id("507329")
                  .filename("Velvet Antenna [[tmdb-507329]].mkv")
                  .build())
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
  @DisplayName("Unsuccessful External ID Extraction Tests")
  class ShouldFailToExtractIdAndSource {

    @Builder
    record TestCase(String testName, String filename) {}

    private TestCase.TestCaseBuilder testCase() {
      return TestCase.builder();
    }

    @TestFactory
    @DisplayName("Should return empty when filename has no supported external ID tag")
    Stream<DynamicNode> shouldReturnEmptyWhenFilenameHasNoSupportedExternalIdTag() {
      return Stream.of(
              testCase()
                  .testName("when given title without external ID tag")
                  .filename(
                      "Slate Garden (2022) imdb tt13581194 [WEBDL-1080p][EAC3 Atmos 5.1][x264]-KLV.mkv")
                  .build(),
              testCase().testName("when given null input").filename(null).build(),
              testCase().testName("when given empty input").filename("").build(),
              testCase().testName("when given blank input").filename(" ").build(),
              testCase()
                  .testName("when external ID tag has an empty value")
                  .filename("Velvet Antenna [tmdbid=].mkv")
                  .build(),
              testCase()
                  .testName("when external ID tag is never closed")
                  .filename("Velvet Antenna [tmdb-507329.mkv")
                  .build())
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
