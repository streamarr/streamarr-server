package com.streamarr.server.services.parsers.show;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
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
  @DisplayName("Should successfully extract season number")
  class SuccessfulExtractionTests {

    record TestCase(String folderName, int seasonNumber, boolean isSeasonDirectory) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
              new TestCase("Season 1", 1, true),
              new TestCase("Season 2", 2, true),
              new TestCase("Season 02", 2, true),
              new TestCase("S02", 2, true),
              new TestCase("2", 2, true),
              new TestCase("Season 2009", 2009, true),
              new TestCase("Season1", 1, true),
              new TestCase("The.Wonder.Years.S04.PDTV.x264-JCH", 4, true),
              new TestCase("Season 7 (2016)", 7, false),
              new TestCase("Staffel 7 (2016)", 7, false),
              new TestCase("Stagione 7 (2016)", 7, false),
              new TestCase("3.Staffel", 3, false),
              new TestCase("extras", 0, true),
              new TestCase("specials", 0, true),

              // i18n season folder names
              new TestCase("Sæson 3", 3, true),
              new TestCase("Temporada 5", 5, true),
              new TestCase("Saison 2", 2, true),
              new TestCase("Series 4", 4, true),
              new TestCase("Сезон 1", 1, true))
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
  @DisplayName("Should fail to extract season number")
  class UnsuccessfulExtractionTests {

    record TestCase(String folderName, boolean isSeasonDirectory) {}

    @TestFactory
    Stream<DynamicNode> tests() {
      return Stream.of(
              new TestCase("Season (8)", false),
              new TestCase("s06e05", false),
              new TestCase(
                  "The.Legend.of.Condor.Heroes.2017.V2.web-dl.1080p.h264.aac-hdctv", false))
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
