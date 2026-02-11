package com.streamarr.server.services.parsers.show;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.ExternalSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Series Folder Name Parser Tests")
class SeriesFolderNameParserTest {

  private final SeriesFolderNameParser parser = new SeriesFolderNameParser();

  @Test
  @DisplayName("Should extract title, year, and IMDB external ID from folder name")
  void shouldExtractTitleYearAndImdbExternalIdFromFolderName() {
    var result = parser.parse("Love, Death & Robots (2019) [imdb-tt9561862]");

    assertThat(result.title()).isEqualTo("Love, Death & Robots");
    assertThat(result.year()).isEqualTo("2019");
    assertThat(result.externalId()).isEqualTo("tt9561862");
    assertThat(result.externalSource()).isEqualTo(ExternalSourceType.IMDB);
  }

  @Test
  @DisplayName("Should extract title and year when no external ID tag present")
  void shouldExtractTitleAndYearWhenNoExternalIdTagPresent() {
    var result = parser.parse("Breaking Bad (2008)");

    assertThat(result.title()).isEqualTo("Breaking Bad");
    assertThat(result.year()).isEqualTo("2008");
    assertThat(result.externalId()).isNull();
    assertThat(result.externalSource()).isNull();
  }

  @Test
  @DisplayName("Should extract only title when no year or external ID present")
  void shouldExtractOnlyTitleWhenNoYearOrExternalIdPresent() {
    var result = parser.parse("The Wire");

    assertThat(result.title()).isEqualTo("The Wire");
    assertThat(result.year()).isNull();
    assertThat(result.externalId()).isNull();
    assertThat(result.externalSource()).isNull();
  }

  @Test
  @DisplayName("Should extract title, year, and IMDB external ID from Hilda folder")
  void shouldExtractTitleYearAndImdbExternalIdFromHildaFolder() {
    var result = parser.parse("Hilda (2018) [imdb-tt6385540]");

    assertThat(result.title()).isEqualTo("Hilda");
    assertThat(result.year()).isEqualTo("2018");
    assertThat(result.externalId()).isEqualTo("tt6385540");
    assertThat(result.externalSource()).isEqualTo(ExternalSourceType.IMDB);
  }

  @Test
  @DisplayName("Should extract title and TMDB external ID when no year present")
  void shouldExtractTitleAndTmdbExternalIdWhenNoYearPresent() {
    var result = parser.parse("Some Show [tmdb-12345]");

    assertThat(result.title()).isEqualTo("Some Show");
    assertThat(result.year()).isNull();
    assertThat(result.externalId()).isEqualTo("12345");
    assertThat(result.externalSource()).isEqualTo(ExternalSourceType.TMDB);
  }

  @Test
  @DisplayName("Should handle curly brace external ID tags")
  void shouldHandleCurlyBraceExternalIdTags() {
    var result = parser.parse("Show Name (2020) {imdb-tt1234567}");

    assertThat(result.title()).isEqualTo("Show Name");
    assertThat(result.year()).isEqualTo("2020");
    assertThat(result.externalId()).isEqualTo("tt1234567");
    assertThat(result.externalSource()).isEqualTo(ExternalSourceType.IMDB);
  }

  @Test
  @DisplayName("Should handle space separator in external ID tag")
  void shouldHandleSpaceSeparatorInExternalIdTag() {
    var result = parser.parse("Show Name [imdb tt1234567]");

    assertThat(result.title()).isEqualTo("Show Name");
    assertThat(result.year()).isNull();
    assertThat(result.externalId()).isEqualTo("tt1234567");
    assertThat(result.externalSource()).isEqualTo(ExternalSourceType.IMDB);
  }

  @Test
  @DisplayName("Should extract external ID from parenthesis brackets")
  void shouldExtractExternalIdFromParenthesisBrackets() {
    var result = parser.parse("Show (2020) (imdb-tt1234567)");

    assertThat(result.title()).isEqualTo("Show");
    assertThat(result.year()).isEqualTo("2020");
    assertThat(result.externalId()).isEqualTo("tt1234567");
    assertThat(result.externalSource()).isEqualTo(ExternalSourceType.IMDB);
  }

  @Test
  @DisplayName("Should extract external ID with equals separator")
  void shouldExtractExternalIdWithEqualsSeparator() {
    var result = parser.parse("Show [imdb=tt1234567]");

    assertThat(result.title()).isEqualTo("Show");
    assertThat(result.year()).isNull();
    assertThat(result.externalId()).isEqualTo("tt1234567");
    assertThat(result.externalSource()).isEqualTo(ExternalSourceType.IMDB);
  }

  @Test
  @DisplayName("Should extract TVDB external ID")
  void shouldExtractTvdbExternalId() {
    var result = parser.parse("Show [tvdb-12345]");

    assertThat(result.title()).isEqualTo("Show");
    assertThat(result.year()).isNull();
    assertThat(result.externalId()).isEqualTo("12345");
    assertThat(result.externalSource()).isEqualTo(ExternalSourceType.TVDB);
  }

  @Test
  @DisplayName("Should extract external ID with imdbid attribute name")
  void shouldExtractExternalIdWithImdbidAttributeName() {
    var result = parser.parse("Show [imdbid-tt1234567]");

    assertThat(result.title()).isEqualTo("Show");
    assertThat(result.year()).isNull();
    assertThat(result.externalId()).isEqualTo("tt1234567");
    assertThat(result.externalSource()).isEqualTo(ExternalSourceType.IMDB);
  }

  @Test
  @DisplayName("Should extract external ID with tmdbid attribute name and equals separator")
  void shouldExtractExternalIdWithTmdbidAttributeNameAndEqualsSeparator() {
    var result = parser.parse("Show [tmdbid=12345]");

    assertThat(result.title()).isEqualTo("Show");
    assertThat(result.year()).isNull();
    assertThat(result.externalId()).isEqualTo("12345");
    assertThat(result.externalSource()).isEqualTo(ExternalSourceType.TMDB);
  }

  @Test
  @DisplayName("Should extract TVDB external ID with tvdbid attribute name in curly braces")
  void shouldExtractTvdbExternalIdWithTvdbidAttributeNameInCurlyBraces() {
    var result = parser.parse("Show (2021) {tvdbid=67890}");

    assertThat(result.title()).isEqualTo("Show");
    assertThat(result.year()).isEqualTo("2021");
    assertThat(result.externalId()).isEqualTo("67890");
    assertThat(result.externalSource()).isEqualTo(ExternalSourceType.TVDB);
  }
}
