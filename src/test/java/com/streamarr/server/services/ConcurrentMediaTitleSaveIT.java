package com.streamarr.server.services;

import static com.streamarr.server.support.HeldCreditInsert.CreditTable.GENRE;
import static com.streamarr.server.support.HeldCreditInsert.CreditTable.PERSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.support.HeldCreditInsert;
import com.streamarr.server.support.OrderedSourceIds;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Saves one media title while another media title's open transaction has inserted the same new
 * genres in the opposite order. The other media title inserts the lower source id, waits until this
 * save waits on it, then inserts the higher source id and commits.
 */
@Tag("IntegrationTest")
@DisplayName("Concurrent Media Title Save Integration Tests")
class ConcurrentMediaTitleSaveIT extends AbstractIntegrationTest {

  @Autowired private ArtworkRequestService artworkRequestService;
  @Autowired private MovieService movieService;
  @Autowired private SeriesService seriesService;
  @Autowired private MovieRepository movieRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private PersonRepository personRepository;
  @Autowired private DataSource dataSource;

  private Library library;
  private OrderedSourceIds genreIds;

  @BeforeEach
  void setUp() {
    library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    genreIds = OrderedSourceIds.unique();
  }

  @Test
  @DisplayName(
      "Should create movie when another media title is saving the same new genres in opposite"
          + " order")
  void shouldCreateMovieWhenAnotherMediaTitleIsSavingTheSameNewGenresInOppositeOrder()
      throws Exception {
    var mediaFile = mediaFile();
    var metadata =
        metadataFor(
            Movie.builder()
                .title("Created Movie")
                .library(library)
                .genres(genresHigherFirst())
                .build());

    var movie =
        saveWhileAnotherMediaTitleSavesTheGenresInOppositeOrder(
            artworkRun ->
                movieService.createMovieWithAssociations(metadata, mediaFile, artworkRun));

    assertSavedGenres(movieService.findGenres(movie.getId()));
  }

  @Test
  @DisplayName(
      "Should refresh movie when another media title is saving the same new genres in opposite"
          + " order")
  void shouldRefreshMovieWhenAnotherMediaTitleIsSavingTheSameNewGenresInOppositeOrder()
      throws Exception {
    var existing =
        movieRepository.saveAndFlush(
            Movie.builder().title("Refreshed Movie").library(library).build());
    var metadata =
        metadataFor(Movie.builder().title("Refreshed Movie").genres(genresHigherFirst()).build());

    var movie =
        saveWhileAnotherMediaTitleSavesTheGenresInOppositeOrder(
            artworkRun -> movieService.refreshMovieMetadata(existing, metadata, artworkRun));

    assertSavedGenres(movieService.findGenres(movie.getId()));
  }

  @Test
  @DisplayName(
      "Should create series when another media title is saving the same new genres in opposite"
          + " order")
  void shouldCreateSeriesWhenAnotherMediaTitleIsSavingTheSameNewGenresInOppositeOrder()
      throws Exception {
    var metadata =
        metadataFor(
            Series.builder()
                .title("Created Series")
                .library(library)
                .genres(genresHigherFirst())
                .build());

    var series =
        saveWhileAnotherMediaTitleSavesTheGenresInOppositeOrder(
            artworkRun -> seriesService.createSeriesWithAssociations(metadata, artworkRun));

    assertSavedGenres(seriesService.findGenres(series.getId()));
  }

  @Test
  @DisplayName(
      "Should refresh series when another media title is saving the same new genres in opposite"
          + " order")
  void shouldRefreshSeriesWhenAnotherMediaTitleIsSavingTheSameNewGenresInOppositeOrder()
      throws Exception {
    var existing =
        seriesRepository.saveAndFlush(
            Series.builder().title("Refreshed Series").library(library).build());
    var metadata =
        metadataFor(Series.builder().title("Refreshed Series").genres(genresHigherFirst()).build());

    var series =
        saveWhileAnotherMediaTitleSavesTheGenresInOppositeOrder(
            artworkRun -> seriesService.refreshSeriesMetadata(existing, metadata, artworkRun));

    assertSavedGenres(seriesService.findGenres(series.getId()));
  }

  @Test
  @DisplayName(
      "Should refresh movie when another media title renamed the same person and is inserting the"
          + " same new person")
  void shouldRefreshMovieWhenAnotherMediaTitleRenamedTheSamePersonAndIsInsertingTheSameNewPerson()
      throws Exception {
    var personIds = OrderedSourceIds.unique();
    personRepository.saveAndFlush(
        Person.builder().sourceId(personIds.lower()).name("Earlier Name").build());
    var existing =
        movieRepository.saveAndFlush(
            Movie.builder().title("Refreshed Movie").library(library).build());
    var metadata =
        metadataFor(
            Movie.builder()
                .title("Refreshed Movie")
                .cast(
                    List.of(
                        Person.builder().sourceId(personIds.lower()).name("Renamed").build(),
                        Person.builder().sourceId(personIds.higher()).name("New Actor").build()))
                .build());

    Movie movie;
    try (var artworkRun = artworkRequestService.openRun("refresh", ImageRefreshMode.PRESERVE);
        var otherMediaTitle = HeldCreditInsert.open(dataSource)) {
      otherMediaTitle.lockForRename(PERSON, personIds.lower());

      movie =
          otherMediaTitle.commitAfterSaveWaits(
              () -> movieService.refreshMovieMetadata(existing, metadata, artworkRun),
              held -> held.insert(PERSON, personIds.higher()));
    }

    assertThat(movieService.findCast(movie.getId()))
        .extracting(Person::getSourceId, Person::getName)
        .containsExactly(
            tuple(personIds.lower(), "Renamed"), tuple(personIds.higher(), "New Actor"));
  }

  private <T> T saveWhileAnotherMediaTitleSavesTheGenresInOppositeOrder(
      Function<ArtworkRun, T> mediaTitleSave) throws SQLException {
    try (var artworkRun = artworkRequestService.openRun("scan", ImageRefreshMode.PRESERVE);
        var otherMediaTitle = HeldCreditInsert.open(dataSource)) {
      otherMediaTitle.insert(GENRE, genreIds.lower());

      return otherMediaTitle.commitAfterSaveWaits(
          () -> mediaTitleSave.apply(artworkRun), held -> held.insert(GENRE, genreIds.higher()));
    }
  }

  private static <T> MetadataResult<T> metadataFor(T mediaTitle) {
    return new MetadataResult<>(mediaTitle, List.of(), Map.of(), Map.of());
  }

  private LinkedHashSet<Genre> genresHigherFirst() {
    var genres = new LinkedHashSet<Genre>();
    genres.add(Genre.builder().sourceId(genreIds.higher()).name("Higher Genre").build());
    genres.add(Genre.builder().sourceId(genreIds.lower()).name("Lower Genre").build());
    return genres;
  }

  private void assertSavedGenres(List<Genre> savedGenres) {
    assertThat(savedGenres)
        .extracting(Genre::getSourceId)
        .containsExactlyInAnyOrder(genreIds.lower(), genreIds.higher());
  }

  private MediaFile mediaFile() {
    return mediaFileRepository.save(
        MediaFile.builder()
            .libraryId(library.getId())
            .status(MediaFileStatus.UNMATCHED)
            .filename("created-movie.mkv")
            .filepathUri("file:///library/" + UUID.randomUUID() + "/created-movie.mkv")
            .build());
  }
}
