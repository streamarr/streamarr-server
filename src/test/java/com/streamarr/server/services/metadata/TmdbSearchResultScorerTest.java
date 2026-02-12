package com.streamarr.server.services.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.metadata.TmdbSearchResultScorer.CandidateResult;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("TMDB Search Result Scorer Tests")
class TmdbSearchResultScorerTest {

  @Nested
  @DisplayName("Title similarity scoring")
  class TitleSimilarityTests {

    @Test
    @DisplayName("Should score exact title match highest")
    void shouldScoreExactTitleMatchHighest() {
      var candidates = List.of(new CandidateResult("Breaking Bad", "Breaking Bad", "2008", 500.0));

      var result = TmdbSearchResultScorer.selectBestMatch("Breaking Bad", "2008", candidates);

      assertThat(result).isPresent();
      assertThat(result.getAsInt()).isZero();
    }

    @Test
    @DisplayName("Should score fuzzy title match above threshold")
    void shouldScoreFuzzyTitleMatchAboveThreshold() {
      var candidates = List.of(new CandidateResult("WALL-E", "WALL·E", "2008", 100.0));

      var result = TmdbSearchResultScorer.selectBestMatch("WALL-E", "2008", candidates);

      assertThat(result).isPresent();
    }

    @Test
    @DisplayName("Should normalize titles before comparing")
    void shouldNormalizeTitlesBeforeComparing() {
      var candidates = List.of(new CandidateResult("The Office", "The Office", "2005", 200.0));

      var result = TmdbSearchResultScorer.selectBestMatch("office", null, candidates);

      assertThat(result).isPresent();
      assertThat(result.getAsInt()).isZero();
    }

    @Test
    @DisplayName("Should handle Unicode equivalents via NFKD normalization")
    void shouldHandleUnicodeEquivalentsViaNfkdNormalization() {
      var candidates = List.of(new CandidateResult("8½", "Otto e mezzo", "1963", 50.0));

      var result = TmdbSearchResultScorer.selectBestMatch("8 1/2", "1963", candidates);

      assertThat(result).isPresent();
      assertThat(result.getAsInt()).isZero();
    }

    @Test
    @DisplayName("Should return empty for empty-after-normalization titles")
    void shouldReturnEmptyForEmptyAfterNormalization() {
      var candidates = List.of(new CandidateResult("!!!...", "!!!...", "2020", 100.0));

      var result = TmdbSearchResultScorer.selectBestMatch("!!!...", "2020", candidates);

      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("Year and popularity scoring")
  class YearAndPopularityTests {

    @Test
    @DisplayName("Should prefer result with matching year")
    void shouldPreferResultWithMatchingYear() {
      var candidates =
          List.of(
              new CandidateResult("The Office", "The Office", "2001", 100.0),
              new CandidateResult("The Office", "The Office", "2005", 100.0));

      var result = TmdbSearchResultScorer.selectBestMatch("The Office", "2005", candidates);

      assertThat(result).isPresent();
      assertThat(result.getAsInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should prefer more popular result when title and year are equal")
    void shouldPreferMorePopularResultWhenTitleAndYearEqual() {
      var candidates =
          List.of(
              new CandidateResult("Inception", "Inception", "2010", 10.0),
              new CandidateResult("Inception", "Inception", "2010", 900.0));

      var result = TmdbSearchResultScorer.selectBestMatch("Inception", "2010", candidates);

      assertThat(result).isPresent();
      assertThat(result.getAsInt()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Result selection")
  class ResultSelectionTests {

    @Test
    @DisplayName("Should reject all results below minimum threshold")
    void shouldRejectAllResultsBelowMinimumThreshold() {
      var candidates =
          List.of(new CandidateResult("Interpol Code 8", "Interpol Code 8", "2020", 50.0));

      var result = TmdbSearchResultScorer.selectBestMatch("8½", "1963", candidates);

      assertThat(result).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singleCandidateMatchCases")
    @DisplayName("Should select best match from single candidate")
    void shouldSelectBestMatchFromSingleCandidate(
        String scenario, String query, String queryYear, CandidateResult candidate) {
      var result = TmdbSearchResultScorer.selectBestMatch(query, queryYear, List.of(candidate));

      assertThat(result).isPresent();
      assertThat(result.getAsInt()).isZero();
    }

    static Stream<Arguments> singleCandidateMatchCases() {
      return Stream.of(
          Arguments.of(
              "original name scores higher than primary",
              "Breaking Bad",
              "2008",
              new CandidateResult("Localized Title", "Breaking Bad", "2008", 500.0)),
          Arguments.of(
              "null query year",
              "Breaking Bad",
              null,
              new CandidateResult("Breaking Bad", "Breaking Bad", "2008", 500.0)),
          Arguments.of(
              "null candidate year",
              "Breaking Bad",
              "2008",
              new CandidateResult("Breaking Bad", "Breaking Bad", null, 500.0)));
    }

    @Test
    @DisplayName("Should reject wrong title even with matching year and high popularity")
    void shouldRejectWrongTitleEvenWithMatchingYearAndHighPopularity() {
      var candidates =
          List.of(new CandidateResult("Walls Have Ears", "Walls Have Ears", "2008", 999.0));

      var result = TmdbSearchResultScorer.selectBestMatch("WALL-E", "2008", candidates);

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should select correct match from multiple results")
    void shouldSelectCorrectMatchFromMultipleResults() {
      var candidates =
          List.of(
              new CandidateResult("Interpol Code 8", "Interpol Code 8", "2020", 30.0),
              new CandidateResult("8½", "Otto e mezzo", "1963", 50.0));

      var result = TmdbSearchResultScorer.selectBestMatch("8½", "1963", candidates);

      assertThat(result).isPresent();
      assertThat(result.getAsInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should return empty for empty candidate list")
    void shouldReturnEmptyForEmptyCandidateList() {
      var result = TmdbSearchResultScorer.selectBestMatch("Breaking Bad", "2008", List.of());

      assertThat(result).isEmpty();
    }
  }
}
