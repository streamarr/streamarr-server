package com.streamarr.server.services.parsers.show;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

@Tag("UnitTest")
@DisplayName("Season Path Extraction Tests")
class SeasonPathMetadataParserTest {

  private final SeasonPathMetadataParser seasonPathMetadataParser = new SeasonPathMetadataParser();

  @Test
  @DisplayName("Should return empty season result when folder name is blank")
  void shouldReturnEmptySeasonResultWhenFolderNameIsBlank() {
    var result = seasonPathMetadataParser.parse("").orElseThrow();

    assertThat(result.seasonNumber()).isEmpty();
    assertThat(result.isSeasonFolder()).isFalse();
  }

  @Nested
  @DisplayName("Successful Season Extraction Tests")
  class SuccessfulExtractionTests {

    @Builder
    record TestCase(String folderName, int seasonNumber, boolean isSeasonDirectory) {}

    private TestCase.TestCaseBuilder testCase() {
      return TestCase.builder();
    }

    @TestFactory
    @DisplayName("Should extract season number when folder name matches a supported pattern")
    Stream<DynamicNode> shouldExtractSeasonNumberWhenFolderNameMatchesSupportedPattern() {
      return Stream.of(
              testCase().folderName("Season 1").seasonNumber(1).isSeasonDirectory(true).build(),
              testCase().folderName("Season 2").seasonNumber(2).isSeasonDirectory(true).build(),
              testCase().folderName("Season 02").seasonNumber(2).isSeasonDirectory(true).build(),
              testCase().folderName("S02").seasonNumber(2).isSeasonDirectory(true).build(),
              testCase().folderName("2").seasonNumber(2).isSeasonDirectory(true).build(),
              testCase()
                  .folderName("Season 2009")
                  .seasonNumber(2009)
                  .isSeasonDirectory(true)
                  .build(),
              testCase().folderName("Season1").seasonNumber(1).isSeasonDirectory(true).build(),
              testCase()
                  .folderName("The.Meadow.Years.S04.PDTV.x264-VXK")
                  .seasonNumber(4)
                  .isSeasonDirectory(true)
                  .build(),
              testCase()
                  .folderName("Season 7 (2016)")
                  .seasonNumber(7)
                  .isSeasonDirectory(false)
                  .build(),
              testCase()
                  .folderName("Staffel 7 (2016)")
                  .seasonNumber(7)
                  .isSeasonDirectory(false)
                  .build(),
              testCase()
                  .folderName("Stagione 7 (2016)")
                  .seasonNumber(7)
                  .isSeasonDirectory(false)
                  .build(),
              testCase().folderName("3.Staffel").seasonNumber(3).isSeasonDirectory(false).build(),
              testCase().folderName("extras").seasonNumber(0).isSeasonDirectory(true).build(),
              testCase().folderName("specials").seasonNumber(0).isSeasonDirectory(true).build(),

              // i18n season folder names
              testCase().folderName("Sæson 3").seasonNumber(3).isSeasonDirectory(true).build(),
              testCase().folderName("Temporada 5").seasonNumber(5).isSeasonDirectory(true).build(),
              testCase().folderName("Saison 2").seasonNumber(2).isSeasonDirectory(true).build(),
              testCase().folderName("Series 4").seasonNumber(4).isSeasonDirectory(true).build(),
              testCase().folderName("Сезон 1").seasonNumber(1).isSeasonDirectory(true).build(),

              // A bare numeric folder is trusted as a season, even when it looks like a year.
              testCase().folderName("2009").seasonNumber(2009).isSeasonDirectory(true).build())
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.folderName(),
                      () -> {
                        var result =
                            seasonPathMetadataParser.parse(testCase.folderName()).orElseThrow();

                        assertThat(result.seasonNumber().orElseThrow())
                            .isEqualTo(testCase.seasonNumber());
                        assertThat(result.isSeasonFolder()).isEqualTo(testCase.isSeasonDirectory());
                      }));
    }
  }

  @Nested
  @DisplayName("Unsuccessful Season Extraction Tests")
  class UnsuccessfulExtractionTests {

    @Builder
    record TestCase(String folderName, boolean isSeasonDirectory) {}

    private TestCase.TestCaseBuilder testCase() {
      return TestCase.builder();
    }

    @TestFactory
    @DisplayName("Should return no season number when folder name does not match")
    Stream<DynamicNode> shouldReturnNoSeasonNumberWhenFolderNameDoesNotMatch() {
      return Stream.of(
              testCase().folderName("Season (8)").isSeasonDirectory(false).build(),
              testCase().folderName("s06e05").isSeasonDirectory(false).build(),
              testCase().folderName("2024-01-24 4070 ti overview").isSeasonDirectory(false).build(),
              testCase()
                  .folderName("The.Ballad.of.Marble.Wrens.2017.V2.web-dl.1080p.h264.aac-hdctv")
                  .isSeasonDirectory(false)
                  .build())
          .map(
              testCase ->
                  DynamicTest.dynamicTest(
                      testCase.folderName(),
                      () -> {
                        var result =
                            seasonPathMetadataParser.parse(testCase.folderName()).orElseThrow();

                        assertThat(result.seasonNumber()).isEmpty();
                        assertThat(result.isSeasonFolder()).isEqualTo(testCase.isSeasonDirectory());
                      }));
    }
  }
}
