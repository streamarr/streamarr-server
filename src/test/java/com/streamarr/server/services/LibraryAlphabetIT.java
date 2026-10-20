package com.streamarr.server.services;

import static com.streamarr.server.fixtures.PaginationFixture.buildForwardContinuation;
import static com.streamarr.server.fixtures.PaginationFixture.buildForwardOptions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.library.LetterIndexRecalculator;
import com.streamarr.server.services.library.LibraryCountsService;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaOrder;
import com.streamarr.server.services.pagination.MediaOrderKeys;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.utils.TitleSortUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

// Schema-mutating fixtures use a separate pool without cached result-column metadata.
@TestPropertySource(
    properties = "spring.datasource.hikari.data-source-properties.prepareThreshold=0")
@Transactional
@Tag("IntegrationTest")
class LibraryAlphabetIT extends AbstractIntegrationTest {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private LibraryRepository libraries;
  @Autowired private MovieRepository movies;
  @Autowired private MovieService movieService;
  @Autowired private LetterIndexRecalculator recalculator;
  @Autowired private LibraryCountsService counts;
  @Autowired private MediaOrderKeys orderKeys;

  @ParameterizedTest
  @CsvSource({
    "C,ASC", "C,DESC", "en-US-x-icu,ASC", "en-US-x-icu,DESC",
    "tr-TR-x-icu,ASC", "tr-TR-x-icu,DESC", "az-Latn-x-icu,ASC", "az-Latn-x-icu,DESC"
  })
  @DisplayName("Should keep title order and continuation independent of database collation")
  void shouldKeepTitleOrderAndContinuationIndependentOfDatabaseCollation(
      String collation, SortDirection direction) {
    jdbc.execute(
        "ALTER TABLE base_collectable ALTER COLUMN title_sort TYPE TEXT COLLATE \""
            + collation
            + "\"");
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    for (var title : List.of("Zulu", "INDIGO", "Alpha")) {
      movies.saveAndFlush(Movie.builder().title(title).titleSort(title).library(library).build());
    }

    var filter = MediaFilter.builder().libraryId(library.getId()).sortDirection(direction).build();
    var expected =
        direction == SortDirection.ASC
            ? List.of("Alpha", "INDIGO", "Zulu")
            : List.of("Zulu", "INDIGO", "Alpha");
    var complete = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));
    assertThat(complete.items())
        .extracting(item -> item.item().getTitle())
        .containsExactlyElementsOf(expected);

    var orders =
        complete.items().stream()
            .map(item -> MediaOrder.from(filter, item.item().getId(), item.sortValue()))
            .collect(Collectors.toSet());
    var keys = orderKeys.getKeys(orders);
    assertThat(
            complete.items().stream()
                .sorted(
                    Comparator.comparing(
                        item ->
                            keys.get(
                                MediaOrder.from(filter, item.item().getId(), item.sortValue()))))
                .map(item -> item.item().getTitle())
                .toList())
        .containsExactlyElementsOf(expected);

    var traversed = new ArrayList<String>();
    var options = buildForwardOptions(1, filter);
    for (int index = 0; index < expected.size(); index++) {
      var page = movieService.getMoviesWithFilter(options);
      assertThat(page.items()).hasSize(1);
      var item = page.items().getFirst();
      traversed.add(item.item().getTitle());
      options = buildForwardContinuation(1, filter, item);
      assertThat(page.hasNextPage()).isEqualTo(index < expected.size() - 1);
    }

    assertThat(traversed).containsExactlyElementsOf(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"C", "en-US-x-icu", "tr-TR-x-icu"})
  @DisplayName("Should use ASCII alphabet membership before Unicode case folding")
  void shouldUseAsciiAlphabetMembershipBeforeUnicodeCaseFolding(String collation) {
    jdbc.execute(
        "ALTER TABLE base_collectable ALTER COLUMN title_sort TYPE TEXT COLLATE \""
            + collation
            + "\"");
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    for (var title : List.of("İstanbul", "Kelvin", "indigo", "Kelvin")) {
      movies.saveAndFlush(Movie.builder().title(title).titleSort(title).library(library).build());
    }

    recalculator.recalculateLetterIndex(library.getId());

    var totals = counts.getItemCounts(List.of(library.getId())).get(library.getId());
    assertThat(totals.total()).isEqualTo(4);
    assertThat(totals.alphabetIndex())
        .extracting(LibraryMetadata::getLetter, LibraryMetadata::getItemCount)
        .containsExactlyInAnyOrder(
            tuple(AlphabetLetter.HASH, 2), tuple(AlphabetLetter.I, 1), tuple(AlphabetLetter.K, 1));
    var expected =
        Map.of(
            AlphabetLetter.HASH, List.of("İstanbul", "Kelvin"),
            AlphabetLetter.I, List.of("indigo"),
            AlphabetLetter.K, List.of("Kelvin"));
    expected.forEach(
        (letter, titles) -> {
          var page =
              movieService.getMoviesWithFilter(
                  buildForwardOptions(
                      10,
                      MediaFilter.builder()
                          .libraryId(library.getId())
                          .sortBy(OrderMediaBy.ADDED)
                          .startLetter(letter)
                          .build()));
          assertThat(page.items())
              .extracting(item -> item.item().getTitle())
              .as("titles in %s under %s", letter, collation)
              .containsExactlyInAnyOrderElementsOf(titles);
        });
  }

  @ParameterizedTest
  @EnumSource(
      value = SortDirection.class,
      names = {"ASC", "DESC"})
  @DisplayName("Should preserve null-title placement when jumping to a letter")
  void shouldPreserveNullTitlePlacementWhenJumpingToLetter(SortDirection direction) {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    movies.saveAllAndFlush(
        List.of(
            Movie.builder().title("Unnamed").library(library).build(),
            Movie.builder().title("Alpha").titleSort("Alpha").library(library).build()));

    var items =
        movieService.getMoviesWithFilter(
            buildForwardOptions(
                10,
                MediaFilter.builder()
                    .libraryId(library.getId())
                    .startLetter(AlphabetLetter.A)
                    .sortDirection(direction)
                    .build()));

    var expectedTitles =
        direction == SortDirection.ASC ? List.of("Alpha", "Unnamed") : List.of("Alpha");
    assertThat(items.items())
        .extracting(item -> item.item().getTitle())
        .containsExactlyElementsOf(expectedTitles);
    assertThat(items.hasPreviousPage()).isEqualTo(direction == SortDirection.DESC);
    assertThat(items.hasNextPage()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"C", "en-US-x-icu"})
  @DisplayName(
      "Should count an accented title under its base letter when its sort title is encoded")
  void shouldCountAccentedTitleUnderItsBaseLetterWhenItsSortTitleIsEncoded(String collation) {
    jdbc.execute(
        "ALTER TABLE base_collectable ALTER COLUMN title_sort TYPE TEXT COLLATE \""
            + collation
            + "\"");
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    movies.saveAndFlush(Movie.builder().title("Unnamed").library(library).build());
    saveEncodedMovies(library, List.of("Élite Harbor", "Alpha"));
    recalculator.recalculateLetterIndex(library.getId());

    var totals = counts.getItemCounts(List.of(library.getId())).get(library.getId());
    var items =
        movieService.getMoviesWithFilter(
            buildForwardOptions(
                10,
                MediaFilter.builder()
                    .libraryId(library.getId())
                    .sortBy(OrderMediaBy.ADDED)
                    .startLetter(AlphabetLetter.HASH)
                    .build()));

    assertThat(totals.total()).isEqualTo(3);
    assertThat(totals.alphabetIndex())
        .extracting(LibraryMetadata::getLetter, LibraryMetadata::getItemCount)
        .containsExactlyInAnyOrder(
            tuple(AlphabetLetter.HASH, 1), tuple(AlphabetLetter.A, 1), tuple(AlphabetLetter.E, 1));
    assertThat(items.items()).extracting(item -> item.item().getTitle()).containsExactly("Unnamed");
  }

  @ParameterizedTest
  @EnumSource(
      value = SortDirection.class,
      names = {"ASC", "DESC"})
  @DisplayName("Should include an accented title when jumping to its base letter by title")
  void shouldIncludeAccentedTitleWhenJumpingToItsBaseLetterByTitle(SortDirection direction) {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    saveEncodedMovies(library, List.of("Delta", "Élite Harbor", "Echo", "Foxtrot"));

    var page =
        movieService.getMoviesWithFilter(
            buildForwardOptions(
                10,
                MediaFilter.builder()
                    .libraryId(library.getId())
                    .sortDirection(direction)
                    .startLetter(AlphabetLetter.E)
                    .build()));

    var expected =
        direction == SortDirection.ASC
            ? List.of("Echo", "Élite Harbor", "Foxtrot")
            : List.of("Élite Harbor", "Echo", "Delta");
    assertThat(page.items())
        .extracting(item -> item.item().getTitle())
        .containsExactlyElementsOf(expected);
  }

  @Test
  @DisplayName("Should place a title under its first letter when it starts with characters above z")
  void shouldPlaceTitleUnderItsFirstLetterWhenItStartsWithCharactersAboveZ() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    saveEncodedMovies(library, List.of("~Tilde Tales", "{Brace} Yourself", "Alpha"));
    recalculator.recalculateLetterIndex(library.getId());

    var totals = counts.getItemCounts(List.of(library.getId())).get(library.getId());
    var page =
        movieService.getMoviesWithFilter(
            buildForwardOptions(
                10,
                MediaFilter.builder()
                    .libraryId(library.getId())
                    .startLetter(AlphabetLetter.T)
                    .build()));

    assertThat(totals.alphabetIndex())
        .extracting(LibraryMetadata::getLetter, LibraryMetadata::getItemCount)
        .containsExactlyInAnyOrder(
            tuple(AlphabetLetter.A, 1), tuple(AlphabetLetter.B, 1), tuple(AlphabetLetter.T, 1));
    assertThat(page.items())
        .extracting(item -> item.item().getTitle())
        .containsExactly("~Tilde Tales");
    assertThat(page.hasPreviousPage()).isTrue();
  }

  @Test
  @DisplayName("Should sum letter counts to the total when HASH holds only non-Latin titles")
  void shouldSumLetterCountsToTotalWhenHashHoldsOnlyNonLatinTitles() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    saveEncodedMovies(
        library, List.of("Ωmega Point", "Жёлтый Дом", "東京の空", "Alpha", "Échos du Nord"));
    recalculator.recalculateLetterIndex(library.getId());

    var totals = counts.getItemCounts(List.of(library.getId())).get(library.getId());

    assertThat(totals.alphabetIndex())
        .extracting(LibraryMetadata::getLetter, LibraryMetadata::getItemCount)
        .containsExactlyInAnyOrder(
            tuple(AlphabetLetter.HASH, 3), tuple(AlphabetLetter.A, 1), tuple(AlphabetLetter.E, 1));
    assertThat(totals.total()).isEqualTo(5);
  }

  private void saveEncodedMovies(Library library, List<String> titles) {
    for (var title : titles) {
      movies.saveAndFlush(
          Movie.builder()
              .title(title)
              .titleSort(TitleSortUtil.computeTitleSort(title))
              .library(library)
              .build());
    }
  }
}
