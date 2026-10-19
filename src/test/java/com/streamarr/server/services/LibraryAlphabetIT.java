package com.streamarr.server.services;

import static com.streamarr.server.fixtures.PaginationFixture.buildForwardOptions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.library.LetterIndexRecalculator;
import com.streamarr.server.services.library.LibraryCountsService;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import java.util.List;
import java.util.Map;
import org.jooq.SortOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@Tag("IntegrationTest")
class LibraryAlphabetIT extends AbstractIntegrationTest {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private LibraryRepository libraries;
  @Autowired private MovieRepository movies;
  @Autowired private MovieService movieService;
  @Autowired private LetterIndexRecalculator recalculator;
  @Autowired private LibraryCountsService counts;

  @ParameterizedTest
  @ValueSource(strings = {"C", "en-US-x-icu", "tr-TR-x-icu"})
  @DisplayName("Should use ASCII alphabet membership before Unicode case folding")
  void shouldUseAsciiAlphabetMembershipBeforeUnicodeCaseFolding(String collation) {
    jdbc.execute(
        "ALTER TABLE base_collectable ALTER COLUMN title_sort TYPE VARCHAR(255) COLLATE \""
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
      value = SortOrder.class,
      names = {"ASC", "DESC"})
  @DisplayName("Should preserve null-title placement when jumping to a letter")
  void shouldPreserveNullTitlePlacementWhenJumpingToLetter(SortOrder direction) {
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
        direction == SortOrder.ASC ? List.of("Alpha", "Unnamed") : List.of("Alpha");
    assertThat(items.items())
        .extracting(item -> item.item().getTitle())
        .containsExactlyElementsOf(expectedTitles);
    assertThat(items.hasPreviousPage()).isEqualTo(direction == SortOrder.DESC);
    assertThat(items.hasNextPage()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"C", "en-US-x-icu"})
  @DisplayName("Should include null and accented sort titles in the counted HASH bucket")
  void shouldIncludeNullAndAccentedSortTitlesInCountedHashBucket(String collation) {
    jdbc.execute(
        "ALTER TABLE base_collectable ALTER COLUMN title_sort TYPE VARCHAR(255) COLLATE \""
            + collation
            + "\"");
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    movies.saveAllAndFlush(
        List.of(
            Movie.builder().title("Unnamed").library(library).build(),
            Movie.builder().title("Élite").titleSort("Élite").library(library).build(),
            Movie.builder().title("Alpha").titleSort("Alpha").library(library).build()));
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
        .containsExactlyInAnyOrder(tuple(AlphabetLetter.HASH, 2), tuple(AlphabetLetter.A, 1));
    assertThat(items.items())
        .extracting(item -> item.item().getTitle())
        .containsExactlyInAnyOrder("Unnamed", "Élite");
  }
}
