package com.streamarr.server.services;

import static com.streamarr.server.support.HeldCreditInsert.CreditTable.GENRE;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.support.HeldCreditInsert;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Saves one media title while another title's open transaction has inserted the same new genres in
 * the opposite order. The other title inserts the lower source id, waits until this save waits on
 * it, then inserts the higher source id and commits.
 */
@Tag("IntegrationTest")
@DisplayName("Concurrent Media Title Save Integration Tests")
class ConcurrentTitleSaveIT extends AbstractIntegrationTest {

  private static final Duration SAVE_TIMEOUT = Duration.ofSeconds(15);

  @Autowired private ArtworkService artworkService;
  @Autowired private MovieService movieService;
  @Autowired private SeriesService seriesService;
  @Autowired private MovieRepository movieRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  private Library library;
  private String lowerGenre;
  private String higherGenre;

  @BeforeEach
  void setUp() {
    library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var suffix = UUID.randomUUID();
    lowerGenre = "100-" + suffix;
    higherGenre = "200-" + suffix;
  }

  @Test
  @DisplayName(
      "Should create movie when another title is saving the same new genres in opposite order")
  void shouldCreateMovieWhenAnotherTitleIsSavingTheSameNewGenresInOppositeOrder() throws Exception {
    var mediaFile = mediaFile();
    var metadata =
        metadataFor(
            Movie.builder()
                .title("Created Movie")
                .library(library)
                .genres(genresHigherFirst())
                .build());

    var movie =
        saveWhileAnotherTitleSavesTheGenresInOppositeOrder(
            artworkRun ->
                movieService.createMovieWithAssociations(metadata, mediaFile, artworkRun));

    assertSavedGenres(movieService.findGenres(movie.getId()));
  }

  @Test
  @DisplayName(
      "Should refresh movie when another title is saving the same new genres in opposite order")
  void shouldRefreshMovieWhenAnotherTitleIsSavingTheSameNewGenresInOppositeOrder()
      throws Exception {
    var existing =
        movieRepository.saveAndFlush(
            Movie.builder().title("Refreshed Movie").library(library).build());
    var metadata =
        metadataFor(Movie.builder().title("Refreshed Movie").genres(genresHigherFirst()).build());

    var movie =
        saveWhileAnotherTitleSavesTheGenresInOppositeOrder(
            artworkRun -> movieService.refreshMovieMetadata(existing, metadata, artworkRun));

    assertSavedGenres(movieService.findGenres(movie.getId()));
  }

  @Test
  @DisplayName(
      "Should create series when another title is saving the same new genres in opposite order")
  void shouldCreateSeriesWhenAnotherTitleIsSavingTheSameNewGenresInOppositeOrder()
      throws Exception {
    var metadata =
        metadataFor(
            Series.builder()
                .title("Created Series")
                .library(library)
                .genres(genresHigherFirst())
                .build());

    var series =
        saveWhileAnotherTitleSavesTheGenresInOppositeOrder(
            artworkRun -> seriesService.createSeriesWithAssociations(metadata, artworkRun));

    assertSavedGenres(seriesService.findGenres(series.getId()));
  }

  @Test
  @DisplayName(
      "Should refresh series when another title is saving the same new genres in opposite order")
  void shouldRefreshSeriesWhenAnotherTitleIsSavingTheSameNewGenresInOppositeOrder()
      throws Exception {
    var existing =
        seriesRepository.saveAndFlush(
            Series.builder().title("Refreshed Series").library(library).build());
    var metadata =
        metadataFor(Series.builder().title("Refreshed Series").genres(genresHigherFirst()).build());

    var series =
        saveWhileAnotherTitleSavesTheGenresInOppositeOrder(
            artworkRun -> seriesService.refreshSeriesMetadata(existing, metadata, artworkRun));

    assertSavedGenres(seriesService.findGenres(series.getId()));
  }

  private <T> T saveWhileAnotherTitleSavesTheGenresInOppositeOrder(
      Function<ArtworkRun, T> titleSave) throws SQLException {
    try (var artworkRun = artworkService.openRun("scan", ImageRefreshMode.PRESERVE);
        var otherTitle = HeldCreditInsert.open(dataSource);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      otherTitle.insert(GENRE, lowerGenre);
      var save = CompletableFuture.supplyAsync(() -> titleSave.apply(artworkRun), executor);

      otherTitle.awaitWaitingTransaction();
      otherTitle.insert(GENRE, higherGenre);
      otherTitle.commit();

      assertThat(save).succeedsWithin(SAVE_TIMEOUT);
      return save.join();
    }
  }

  private static <T> MetadataResult<T> metadataFor(T title) {
    return new MetadataResult<>(title, List.of(), Map.of(), Map.of());
  }

  private LinkedHashSet<Genre> genresHigherFirst() {
    var genres = new LinkedHashSet<Genre>();
    genres.add(Genre.builder().sourceId(higherGenre).name("Higher Genre").build());
    genres.add(Genre.builder().sourceId(lowerGenre).name("Lower Genre").build());
    return genres;
  }

  private void assertSavedGenres(List<Genre> savedGenres) {
    assertThat(savedGenres)
        .extracting(Genre::getSourceId)
        .containsExactlyInAnyOrder(lowerGenre, higherGenre);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM genre WHERE source_id IN (?, ?)",
                Integer.class,
                lowerGenre,
                higherGenre))
        .isEqualTo(2);
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
