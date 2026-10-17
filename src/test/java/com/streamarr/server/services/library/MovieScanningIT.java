package com.streamarr.server.services.library;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.streamarr.server.jooq.generated.tables.BaseCollectable.BASE_COLLECTABLE;
import static com.streamarr.server.jooq.generated.tables.Library.LIBRARY;
import static com.streamarr.server.jooq.generated.tables.Movie.MOVIE;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.streamarr.server.config.ArtworkPrioritySchedulers;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fakes.AdvanceableSystemClock;
import com.streamarr.server.fixtures.SessionProgressFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.services.ArtworkPriority;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.ArtworkSchedulerStarter;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.BoundedTask;
import com.streamarr.server.support.LibraryOperationDriver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

@Isolated
@Tag("IntegrationTest")
@DisplayName("Movie Scanning Integration Tests")
class MovieScanningIT extends AbstractScanningIntegrationTest {

  private static final Duration SCAN_BOUND = Duration.ofSeconds(20);
  private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(5);

  // Genres are shared across test classes, and no other test uses this TMDB genre id.
  private static final int UNSHARED_GENRE_ID = 9_000_001;

  @Autowired private LibraryManagementService libraryManagementService;
  @Autowired private LibraryOperationDriver libraryOperationDriver;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuditColumnFixtures auditColumns;
  @Autowired private ApplicationContext applicationContext;
  @Autowired private ArtworkPrioritySchedulers artworkPrioritySchedulers;
  @Autowired private SessionProgressRepository sessionProgressRepository;
  @Autowired private AuthTestSupport authTestSupport;

  @TempDir Path tempDir;

  private AuthTestSupport.TestIdentity admin;

  @BeforeEach
  void cleanupDatabase() {
    wireMock.resetAll();
    jdbcTemplate.update("DELETE FROM primary_artwork_scheduled_tasks");
    mediaFileRepository.deleteAll();
    movieRepository.deleteAll();
    libraryRepository.deleteAll();
  }

  @AfterEach
  void deleteAdmin() {
    if (admin != null) {
      authTestSupport.deleteIdentity(admin);
    }
  }

  @Test
  @DisplayName(
      "Should keep a library's movie, file and resume position when another library holding the"
          + " same movie is removed")
  void shouldKeepLibrarysMovieFileAndResumePositionWhenAnotherLibraryHoldingTheSameMovieIsRemoved()
      throws IOException {
    var removedLibrary = createMovieLibrary(tempDir.resolve("removed"));
    var keptLibrary = createMovieLibrary(tempDir.resolve("kept"));
    createMovieFile("removed/Inception (2010)", "Inception (2010).mkv");
    var keptFile = createMovieFile("kept/Inception (2010)", "Inception (2010).mkv");
    stubTmdbMovieSearch();
    stubTmdbMovieMetadata("27205", "Inception");
    startProbeScheduler();
    libraryOperationDriver.scanToEnd(removedLibrary.getId());
    libraryOperationDriver.scanToEnd(keptLibrary.getId());
    admin = authTestSupport.createAdminIdentity();
    var resumePosition = saveResumePosition(keptFile);

    var removal =
        libraryManagementService.removeLibrary(
            authTestSupport.identityOf(admin), removedLibrary.getId());

    assertThat(removal).isEqualTo(new Outcome.Accepted<>(removedLibrary.getId()));
    assertThat(sessionProgressRepository.findById(resumePosition))
        .as("resume position in the kept library's file")
        .isPresent();
    var keptMovies = movieRepository.findByLibrary_Id(keptLibrary.getId());
    assertThat(keptMovies)
        .as("kept library's movies")
        .extracting(Movie::getTitle)
        .containsExactly("Inception");
    assertThat(mediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(keptFile)))
        .as("kept library's file")
        .get()
        .extracting(MediaFile::getMediaId)
        .isEqualTo(keptMovies.getFirst().getId());
  }

  @Test
  @DisplayName("Should name the System actor as creator when a scan creates a movie and media file")
  void shouldNameSystemActorAsCreatorWhenScanCreatesMovieAndMediaFile() throws Exception {
    var library = createLibraryWithMatchingMovieFile().library();
    deleteUnsharedGenre();
    stubTmdbMovieMetadata("27205", "Inception", UNSHARED_GENRE_ID);
    startProbeScheduler();

    BoundedTask.runWithin(SCAN_BOUND, () -> libraryOperationDriver.scanToEnd(library.getId()));

    var scanned = libraryRepository.findById(library.getId()).orElseThrow();
    assertThat(scanned.getStatus()).isEqualTo(LibraryStatus.HEALTHY);
    assertThat(scanned.getLastModifiedBy()).isEqualTo(SystemActor.ID);
    assertThat(movieRepository.findAll())
        .singleElement()
        .extracting(Movie::getCreatedBy, Movie::getLastModifiedBy)
        .containsOnly(SystemActor.ID);
    assertThat(mediaFileRepository.findByLibraryId(library.getId()))
        .singleElement()
        .extracting(MediaFile::getCreatedBy, MediaFile::getLastModifiedBy)
        .containsOnly(SystemActor.ID);
    assertThat(unsharedGenreCreator()).isEqualTo(SystemActor.ID);
  }

  @Test
  @DisplayName("Should name the System actor when file discovery creates a movie")
  void shouldNameSystemActorWhenFileDiscoveryCreatesMovie() throws IOException {
    var library = createLibraryWithMatchingMovieFile();
    stubTmdbMovieMetadata("27205", "Inception");

    libraryManagementService.processDiscoveredFile(library.id(), library.movieFile());

    assertThat(movieRepository.findAll())
        .singleElement()
        .extracting(Movie::getCreatedBy, Movie::getLastModifiedBy)
        .containsOnly(SystemActor.ID);
    assertThat(mediaFileRepository.findByLibraryId(library.id()))
        .singleElement()
        .extracting(MediaFile::getCreatedBy)
        .isEqualTo(SystemActor.ID);
  }

  @Test
  @DisplayName(
      "Should name the System actor as modifier and keep an empty creator when a refresh updates"
          + " a movie")
  void shouldNameSystemActorAsModifierAndKeepEmptyCreatorWhenRefreshUpdatesMovie()
      throws IOException {
    var library = createLibraryWithMatchingMovieFile();
    stubTmdbMovieMetadata("27205", "Inception");
    libraryManagementService.processDiscoveredFile(library.id(), library.movieFile());
    clearActors();
    stubTmdbMovieMetadata("27205", "Inception Remastered");

    libraryOperationDriver.refreshToEnd(library.id());

    assertThat(movieRepository.findAll())
        .singleElement()
        .satisfies(
            movie -> {
              assertThat(movie.getTitle()).isEqualTo("Inception Remastered");
              assertThat(movie.getCreatedBy()).isNull();
              assertThat(movie.getLastModifiedBy()).isEqualTo(SystemActor.ID);
            });
    assertThat(libraryRepository.findById(library.id()).orElseThrow().getLastModifiedBy())
        .isEqualTo(SystemActor.ID);
  }

  @Test
  @DisplayName("Should name the System actor as modifier when a refresh changes only the summary")
  void shouldNameSystemActorAsModifierWhenARefreshChangesOnlyTheSummary() throws IOException {
    var library = createLibraryWithMatchingMovieFile();
    stubTmdbMovieMetadata("27205", "Inception");
    libraryManagementService.processDiscoveredFile(library.id(), library.movieFile());
    clearActors();
    stubTmdbMovieMetadata(inception().overview("A thief returns for one last job.").build());

    libraryOperationDriver.refreshToEnd(library.id());

    assertThat(movieRepository.findAll())
        .singleElement()
        .satisfies(
            movie -> {
              assertThat(movie.getSummary()).isEqualTo("A thief returns for one last job.");
              assertThat(movie.getLastModifiedBy()).isEqualTo(SystemActor.ID);
            });
  }

  @Test
  @DisplayName("Should fall back to folder name when filename lacks year")
  void shouldFallBackToFolderNameWhenFilenameLacksYear() throws IOException {
    var library = createMovieLibrary();
    var file = createMovieFile("Inception (2010)", "movie.mkv");

    stubTmdbMovieSearch();
    stubTmdbMovieMetadata("27205", "Inception");

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    assertThat(movieRepository.findAll()).hasSize(1);
    var movie = movieRepository.findAll().getFirst();
    assertThat(movie.getTitle()).isEqualTo("Inception");
  }

  @Test
  @DisplayName("Should match media file when folder name fallback succeeds")
  void shouldMatchMediaFileWhenFolderNameFallbackSucceeds() throws IOException {
    var library = createMovieLibrary();
    var file = createMovieFile("Inception (2010)", "movie.mkv");

    stubTmdbMovieSearch();
    stubTmdbMovieMetadata("27205", "Inception");

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    var mediaFile =
        mediaFileRepository.findFirstByFilepathUri(file.toAbsolutePath().toUri().toString());
    assertThat(mediaFile).isPresent();
    assertThat(mediaFile.get().getStatus()).isEqualTo(MediaFileStatus.MATCHED);
  }

  @Test
  @DisplayName("Should parse movie directly from filename when year is present")
  void shouldParseMovieDirectlyFromFilenameWhenYearIsPresent() throws IOException {
    var library = createMovieLibrary();
    var file = createMovieFile("Movies", "Inception (2010).mkv");

    stubTmdbMovieSearch();
    stubTmdbMovieMetadata("27205", "Inception");

    libraryManagementService.processDiscoveredFile(library.getId(), file);

    assertThat(movieRepository.findAll()).hasSize(1);
    assertThat(movieRepository.findAll().getFirst().getTitle()).isEqualTo("Inception");
  }

  @Test
  @DisplayName(
      "Should finish the scan healthy when a retried artwork step stores the results the database rejected")
  void shouldFinishTheScanHealthyWhenARetriedArtworkStepStoresTheResultsTheDatabaseRejected()
      throws Exception {
    var library = createLibraryWithMatchingMovieFile().library();
    stubTmdbMovieMetadata("27205", "Inception");
    startProbeScheduler();
    var rejectedAttempt = new CompletableFuture<ExecutionComplete>();
    var clock = new AdvanceableSystemClock();
    artworkPrioritySchedulers.stop();
    var artworkScheduler =
        ArtworkSchedulerStarter.fromContext(applicationContext).toBuilder()
            .clock(clock)
            .build()
            .start(ArtworkPriority.PRIMARY, completedInto(rejectedAttempt));
    rejectItemErrors();

    try (var scan = BoundedTask.start(() -> libraryOperationDriver.scanToEnd(library.getId()))) {
      rejectedAttempt.get(SCAN_BOUND.toSeconds(), TimeUnit.SECONDS);
      assertThat(failedArtworkAttempts()).isOne();
      assertThat(artworkRetryDelay()).isEqualTo(FIRST_RETRY_DELAY);
      assertThat(libraryRepository.findById(library.getId()).orElseThrow().getStatus())
          .isEqualTo(LibraryStatus.SCANNING);
      acceptItemErrors();
      clock.advance(FIRST_RETRY_DELAY);

      scan.await(SCAN_BOUND);
    } finally {
      acceptItemErrors();
      artworkScheduler.stop();
      artworkPrioritySchedulers.start();
    }

    assertThat(libraryRepository.findById(library.getId()).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.HEALTHY);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM item_result WHERE step = 'ARTWORK' AND outcome ="
                    + " 'UNAVAILABLE' AND item_id = ?",
                Integer.class,
                movieRepository.findAll().getFirst().getId()))
        .isEqualTo(2);
  }

  // --- Helpers ---

  // The scan must insert the genre, so a row left by an earlier run cannot answer for it.
  private void deleteUnsharedGenre() {
    jdbcTemplate.update("DELETE FROM genre WHERE source_id = ?", String.valueOf(UNSHARED_GENRE_ID));
  }

  private UUID unsharedGenreCreator() {
    return jdbcTemplate.queryForObject(
        "SELECT created_by FROM genre WHERE source_id = ?",
        UUID.class,
        String.valueOf(UNSHARED_GENRE_ID));
  }

  // Rows written before the System actor existed have empty audit columns.
  private void clearActors() {
    auditColumns.clearActors(
        BASE_COLLECTABLE, BASE_COLLECTABLE.ID.in(DSL.select(MOVIE.ID).from(MOVIE)));
    auditColumns.clearActors(LIBRARY, DSL.trueCondition());
  }

  // The movie has no poster or backdrop, so its artwork steps must store them as unavailable.
  private void rejectItemErrors() {
    jdbcTemplate.execute(
        """
        CREATE OR REPLACE FUNCTION reject_item_error() RETURNS trigger AS $$
        BEGIN
          RAISE EXCEPTION 'simulated item error write failure';
        END
        $$ LANGUAGE plpgsql
        """);
    jdbcTemplate.execute(
        """
        CREATE TRIGGER reject_item_error BEFORE INSERT OR UPDATE ON item_result
        FOR EACH ROW WHEN (NEW.outcome <> 'SUCCEEDED') EXECUTE FUNCTION reject_item_error()
        """);
  }

  private void acceptItemErrors() {
    jdbcTemplate.execute("DROP TRIGGER IF EXISTS reject_item_error ON item_result");
    jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_item_error()");
  }

  private static AbstractSchedulerListener completedInto(
      CompletableFuture<ExecutionComplete> completed) {
    return new AbstractSchedulerListener() {
      @Override
      public void onExecutionComplete(ExecutionComplete complete) {
        completed.complete(complete);
      }
    };
  }

  private int failedArtworkAttempts() {
    return jdbcTemplate.queryForObject(
        "SELECT coalesce(max(consecutive_failures), 0) FROM primary_artwork_scheduled_tasks",
        Integer.class);
  }

  private Duration artworkRetryDelay() {
    return jdbcTemplate.queryForObject(
        "SELECT execution_time, last_failure FROM primary_artwork_scheduled_tasks",
        (row, _) ->
            Duration.between(
                row.getObject("last_failure", OffsetDateTime.class),
                row.getObject("execution_time", OffsetDateTime.class)));
  }

  private record LibraryWithMovieFile(Library library, Path movieFile) {

    UUID id() {
      return library.getId();
    }
  }

  // TMDB search matches the file. Each test stubs the movie's metadata itself.
  private LibraryWithMovieFile createLibraryWithMatchingMovieFile() throws IOException {
    var library = createMovieLibrary();
    var movieFile = createMovieFile("Inception (2010)", "Inception (2010).mkv");
    stubTmdbMovieSearch();
    return new LibraryWithMovieFile(library, movieFile);
  }

  private UUID saveResumePosition(Path file) {
    var mediaFile =
        mediaFileRepository.findFirstByFilepathUri(FilepathCodec.encode(file)).orElseThrow();
    return sessionProgressRepository
        .saveAndFlush(
            SessionProgressFixture.progressBuilder(admin.profile().getId(), mediaFile.getId())
                .positionSeconds(1800)
                .build())
        .getId();
  }

  private Library createMovieLibrary() throws IOException {
    return createMovieLibrary(tempDir);
  }

  private Library createMovieLibrary(Path root) throws IOException {
    Files.createDirectories(root);
    return libraryRepository.saveAndFlush(
        Library.builder()
            .name("Movies")
            .backend(LibraryBackend.LOCAL)
            .status(LibraryStatus.HEALTHY)
            .filepathUri(FilepathCodec.encode(root))
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .type(MediaType.MOVIE)
            .build());
  }

  private Path createMovieFile(String directory, String filename) throws IOException {
    var dir = tempDir.resolve(directory);
    Files.createDirectories(dir);
    var file = dir.resolve(filename);
    if (!Files.exists(file)) {
      Files.createFile(file);
    }
    return file;
  }

  private void stubTmdbMovieSearch() {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/movie"))
            .withQueryParam("query", equalTo("Inception"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "page": 1,
                          "results": [
                            {
                              "id": 27205,
                              "title": "Inception",
                              "original_title": "Inception",
                              "release_date": "2010-07-16",
                              "popularity": 100.0,
                              "vote_count": 1000,
                              "vote_average": 8.5
                            }
                          ],
                          "total_results": 1,
                          "total_pages": 1
                        }
                        """)));
  }

  private void stubTmdbMovieMetadata(String tmdbId, String title) {
    stubTmdbMovieMetadata(tmdbId, title, 28);
  }

  private void stubTmdbMovieMetadata(String tmdbId, String title, int genreId) {
    stubTmdbMovieMetadata(inception().tmdbId(tmdbId).title(title).genreId(genreId).build());
  }

  private static TmdbMovieStub.TmdbMovieStubBuilder inception() {
    return TmdbMovieStub.builder()
        .tmdbId("27205")
        .title("Inception")
        .genreId(28)
        .overview("A thief who steals corporate secrets through dream-sharing technology.");
  }

  private void stubTmdbMovieMetadata(TmdbMovieStub movie) {
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/" + movie.tmdbId()))
            .withQueryParam("append_to_response", equalTo("credits,release_dates"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": %s,
                          "title": "%s",
                          "original_title": "%s",
                          "release_date": "2010-07-16",
                          "overview": "%s",
                          "runtime": 148,
                          "imdb_id": "tt1375666",
                          "genres": [{"id": %s, "name": "Action"}],
                          "production_companies": [],
                          "credits": {"id": %s, "cast": [], "crew": []},
                          "release_dates": {"results": []}
                        }
                        """
                            .formatted(
                                movie.tmdbId(),
                                movie.title(),
                                movie.title(),
                                movie.overview(),
                                movie.genreId(),
                                movie.tmdbId()))));
  }

  @Builder
  private record TmdbMovieStub(String tmdbId, String title, int genreId, String overview) {}
}
