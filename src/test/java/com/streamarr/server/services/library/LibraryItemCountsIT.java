package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryOperationRepository.StoreOutcome;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.MovieService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
class LibraryItemCountsIT extends AbstractIntegrationTest {

  @Autowired private LibraryRepository libraries;
  @Autowired private SeriesRepository series;
  @Autowired private SeasonRepository seasons;
  @Autowired private EpisodeRepository episodes;
  @Autowired private LibraryManagementService libraryManagement;
  @Autowired private TransactionTemplate transactions;
  @Autowired private MovieRepository movies;
  @Autowired private MediaFileRepository mediaFiles;
  @Autowired private LibraryOperationRepository libraryOperations;
  @Autowired private MovieService movieService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private LibraryCountsService counts;

  @Test
  @DisplayName("Should count only series when a library contains seasons and episodes")
  void shouldCountOnlySeriesWhenALibraryContainsSeasonsAndEpisodes() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var show =
        series.saveAndFlush(
            Series.builder().title("Alpha").titleSort("Alpha").library(library).build());
    var season =
        seasons.saveAndFlush(
            Season.builder()
                .title("Season One")
                .titleSort("Season One")
                .seasonNumber(1)
                .series(show)
                .library(library)
                .build());
    episodes.saveAndFlush(
        Episode.builder()
            .title("Pilot")
            .titleSort("Pilot")
            .episodeNumber(1)
            .season(season)
            .library(library)
            .build());

    assertThat(libraryManagement.getAlphabetIndex(library.getId()))
        .extracting(LibraryMetadata::getLetter, LibraryMetadata::getItemCount)
        .containsExactly(tuple(AlphabetLetter.A, 1));
  }

  @Test
  @DisplayName("Should count a movie once when it has several files")
  void shouldCountAMovieOnceWhenItHasSeveralFiles() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movies.saveAndFlush(
            Movie.builder().title("Alpha").titleSort("Alpha").library(library).build());
    for (var filename : List.of("Alpha (2001) - 1080p.mkv", "Alpha (2001) - 2160p.mkv")) {
      mediaFiles.saveAndFlush(
          MediaFile.builder()
              .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
              .filename(filename)
              .status(MediaFileStatus.MATCHED)
              .libraryId(library.getId())
              .mediaId(movie.getId())
              .build());
    }

    assertThat(totalOf(library)).isOne();
  }

  @Test
  @DisplayName("Should count a movie when its save commits while a scan runs")
  void shouldCountAMovieWhenItsSaveCommitsWhileAScanRuns() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    storeScan(library.getId());

    movies.saveAndFlush(Movie.builder().title("Alpha").titleSort("Alpha").library(library).build());

    assertThat(libraries.findById(library.getId()).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.SCANNING);
    assertThat(lettersOf(library)).containsExactly(tuple(AlphabetLetter.A, 1));
  }

  @Test
  @DisplayName("Should leave the counts unchanged when the save of a movie rolls back")
  void shouldLeaveTheCountsUnchangedWhenTheSaveOfAMovieRollsBack() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    transactions.executeWithoutResult(
        status -> {
          movies.saveAndFlush(
              Movie.builder().title("Alpha").titleSort("Alpha").library(library).build());
          status.setRollbackOnly();
        });

    assertThat(lettersOf(library)).isEmpty();
  }

  @Test
  @DisplayName("Should remove the letter when the last movie that starts with it is deleted")
  void shouldRemoveTheLetterWhenTheLastMovieThatStartsWithItIsDeleted() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var alpha =
        movies.saveAndFlush(
            Movie.builder().title("Alpha").titleSort("Alpha").library(library).build());
    movies.saveAndFlush(Movie.builder().title("Bravo").titleSort("Bravo").library(library).build());

    assertThat(movieService.tryDeleteMovieById(alpha.getId())).isTrue();

    assertThat(lettersOf(library)).containsExactly(tuple(AlphabetLetter.B, 1));
  }

  @Test
  @DisplayName("Should uncount a series when it is deleted")
  void shouldUncountASeriesWhenItIsDeleted() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var show =
        series.saveAndFlush(
            Series.builder().title("Alpha").titleSort("Alpha").library(library).build());
    assertThat(lettersOf(library)).containsExactly(tuple(AlphabetLetter.A, 1));

    series.delete(show);

    assertThat(lettersOf(library)).isEmpty();
  }

  @Test
  @DisplayName("Should uncount a movie when the delete of its catalog row cascades to the movie")
  void shouldUncountAMovieWhenTheDeleteOfItsCatalogRowCascadesToTheMovie() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var alpha =
        movies.saveAndFlush(
            Movie.builder().title("Alpha").titleSort("Alpha").library(library).build());
    assertThat(lettersOf(library)).containsExactly(tuple(AlphabetLetter.A, 1));

    transactions.executeWithoutResult(
        _ -> jdbc.update("DELETE FROM base_collectable WHERE id = ?", alpha.getId()));

    assertThat(movies.findById(alpha.getId())).isEmpty();
    assertThat(lettersOf(library)).isEmpty();
  }

  @Test
  @DisplayName("Should move the count to the new letter when a title changes its first letter")
  void shouldMoveTheCountToTheNewLetterWhenATitleChangesItsFirstLetter() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movies.saveAndFlush(
            Movie.builder().title("Alpha").titleSort("Alpha").library(library).build());
    movies.saveAndFlush(Movie.builder().title("Bravo").titleSort("Bravo").library(library).build());

    movie.setTitleSort("Zulu");
    movies.saveAndFlush(movie);

    assertThat(lettersOf(library))
        .containsExactly(tuple(AlphabetLetter.B, 1), tuple(AlphabetLetter.Z, 1));
  }

  @Test
  @DisplayName("Should keep one count when a title changes within its letter")
  void shouldKeepOneCountWhenATitleChangesWithinItsLetter() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movies.saveAndFlush(
            Movie.builder().title("Alpha").titleSort("Alpha").library(library).build());

    movie.setTitleSort("Avalon");
    movies.saveAndFlush(movie);

    assertThat(lettersOf(library)).containsExactly(tuple(AlphabetLetter.A, 1));
  }

  private List<Tuple> lettersOf(Library library) {
    return libraryManagement.getAlphabetIndex(library.getId()).stream()
        .map(letter -> tuple(letter.getLetter(), letter.getItemCount()))
        .toList();
  }

  private int totalOf(Library library) {
    return counts.getItemCounts(Set.of(library.getId())).get(library.getId()).total();
  }

  private UUID storeScan(UUID libraryId) {
    var outcome =
        transactions.execute(
            _ -> libraryOperations.storeLibraryOperation(NewLibraryOperation.scan(libraryId)));
    return switch (outcome) {
      case StoreOutcome.Stored(var stored, _) -> stored.id();
      case StoreOutcome.Rejected(var rejection) ->
          throw new AssertionError("The scan was not stored: " + rejection);
      case null -> throw new AssertionError("The store returned nothing");
    };
  }
}
