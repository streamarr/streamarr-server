package com.streamarr.server.services.metadata;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.streamarr.server.fakes.TestImages.createTestImage;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitWaitersBehind;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.streamarr.server.AbstractWireMockIntegrationTest;
import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.ImageRepository;
import com.streamarr.server.repositories.media.ItemResultRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.ArtworkFetcher;
import com.streamarr.server.services.ArtworkResult;
import com.streamarr.server.services.ArtworkSources;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Artwork Result Persistence Integration Tests")
class ArtworkResultPersistenceIT extends AbstractWireMockIntegrationTest {

  private static final Instant FIRST_ATTEMPT = Instant.parse("2026-09-23T10:00:00Z");
  private static final Instant SECOND_ATTEMPT = Instant.parse("2026-09-23T11:00:00Z");
  private static final Instant THIRD_ATTEMPT = Instant.parse("2026-09-23T12:00:00Z");

  @Autowired private ArtworkFetcher artworkFetcher;
  @Autowired private ImageRepository imageRepository;
  @Autowired private ItemResultRepository itemResults;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MovieService movieService;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private ImageProperties imageProperties;

  private UUID entityId;

  @BeforeEach
  void setUp() {
    wireMock.resetAll();
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    entityId =
        movieRepository
            .saveAndFlush(Movie.builder().title("Artwork").library(library).build())
            .getId();
  }

  @Test
  @DisplayName("Should record the stored artwork when replacements commit out of attempt order")
  void shouldRecordTheStoredArtworkWhenReplacementsCommitOutOfAttemptOrder() {
    stubImage("/new.jpg");
    stubImage("/old.jpg");
    artworkFetcher.fetch(posterArtwork("/new.jpg"), ImageRefreshMode.PRESERVE, SECOND_ATTEMPT);

    artworkFetcher.fetch(posterArtwork("/old.jpg"), ImageRefreshMode.FORCE_REFRESH, FIRST_ATTEMPT);

    assertThat(storedPosterKeys()).containsOnly("/old.jpg");
    assertThat(posterResult().sourceKey()).isEqualTo("/old.jpg");
    assertThat(posterResult().outcome()).isEqualTo(new ItemOutcome.Succeeded());
  }

  @Test
  @DisplayName("Should retry artwork whose result could not be recorded with the saved image")
  void shouldRetryArtworkWhoseResultCouldNotBeRecordedWithTheSavedImage() {
    wireMock.stubFor(get(urlEqualTo("/poster.jpg")).willReturn(aResponse().withStatus(503)));
    artworkFetcher.fetch(posterArtwork("/poster.jpg"), ImageRefreshMode.PRESERVE, FIRST_ATTEMPT);
    stubImage("/poster.jpg");

    rejectSucceededResultsWhile(
        () ->
            catchThrowable(
                () ->
                    artworkFetcher.fetch(
                        posterArtwork("/poster.jpg"), ImageRefreshMode.PRESERVE, SECOND_ATTEMPT)));
    artworkFetcher.fetch(posterArtwork("/poster.jpg"), ImageRefreshMode.PRESERVE, THIRD_ATTEMPT);

    assertThat(storedPosterKeys()).containsOnly("/poster.jpg");
    assertThat(posterResult().outcome()).isEqualTo(new ItemOutcome.Succeeded());
    assertThat(posterResult().attemptedAt()).isEqualTo(THIRD_ATTEMPT);
  }

  @Test
  @DisplayName("Should record a failed save when the saved image's result cannot be recorded")
  void shouldRecordAFailedSaveWhenTheSavedImagesResultCannotBeRecorded() {
    stubImage("/poster.jpg");
    var results = new ArrayList<ArtworkResult>();

    rejectSucceededResultsWhile(
        () ->
            results.addAll(
                artworkFetcher.fetch(
                    posterArtwork("/poster.jpg"), ImageRefreshMode.PRESERVE, FIRST_ATTEMPT)));

    assertThat(results)
        .filteredOn(result -> result.imageType() == ImageType.POSTER)
        .singleElement()
        .isInstanceOf(ArtworkResult.Failed.class);
    assertThat(storedPosterKeys()).isEmpty();
    assertThat(posterResult().outcome())
        .isInstanceOfSatisfying(
            ItemOutcome.Failed.class,
            failed -> assertThat(failed.reason()).isEqualTo(ItemFailureReason.TEMPORARY));
    assertThat(posterResult().attemptedAt()).isEqualTo(FIRST_ATTEMPT);
  }

  @Test
  @DisplayName("Should discard saved artwork when a delete of its movie commits first")
  void shouldDiscardSavedArtworkWhenADeleteOfItsMovieCommitsFirst() throws Exception {
    stubImage("/poster.jpg");
    var deletePid = new CompletableFuture<Integer>();
    var commitDelete = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var delete =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      _ -> {
                        movieService.deleteMovieById(entityId);
                        deletePid.complete(
                            jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        awaitLatch(commitDelete);
                      }));
      var blockerPid = deletePid.get(10, TimeUnit.SECONDS);

      var fetch =
          executor.submit(
              () ->
                  artworkFetcher.fetch(
                      posterArtwork("/poster.jpg"), ImageRefreshMode.PRESERVE, FIRST_ATTEMPT));
      awaitWaitersBehind(jdbcTemplate, blockerPid, 1);
      commitDelete.countDown();
      delete.get(10, TimeUnit.SECONDS);

      assertThat(fetch.get(10, TimeUnit.SECONDS))
          .filteredOn(result -> result.imageType() == ImageType.POSTER)
          .singleElement()
          .isInstanceOf(ArtworkResult.Failed.class);
    }

    assertThat(storedPosterKeys()).isEmpty();
    assertThat(itemResults.findByItem(entityId, ImageEntityType.MOVIE)).isEmpty();
    assertThat(storedArtworkFiles()).isEmpty();
  }

  @Test
  @DisplayName("Should delete artwork that commits while a delete of its movie waits")
  void shouldDeleteArtworkThatCommitsWhileADeleteOfItsMovieWaits() throws Exception {
    stubImage("/poster.jpg");
    itemResults.trySave(
        ItemResult.builder()
            .itemId(entityId)
            .itemType(ImageEntityType.MOVIE)
            .step(ItemStep.ARTWORK)
            .imageType(ImageType.POSTER)
            .outcome(new ItemOutcome.Unavailable())
            .attemptedAt(FIRST_ATTEMPT)
            .build());
    var posterResult =
        RowLockTarget.builder()
            .dataSource(dataSource)
            .table("item_result")
            .keyColumn("item_id")
            .rowId(entityId)
            .build();

    try (var resultLock = lockRow(posterResult);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var fetch =
          executor.submit(
              () ->
                  artworkFetcher.fetch(
                      posterArtwork("/poster.jpg"), ImageRefreshMode.PRESERVE, SECOND_ATTEMPT));
      awaitWaitersBehind(jdbcTemplate, resultLock.backendPid(), 1);
      var delete = executor.submit(() -> movieService.deleteMovieById(entityId));
      awaitWaitersBehind(jdbcTemplate, resultLock.backendPid(), 2);
      resultLock.release();

      assertThat(fetch.get(10, TimeUnit.SECONDS))
          .filteredOn(result -> result.imageType() == ImageType.POSTER)
          .singleElement()
          .isInstanceOf(ArtworkResult.Saved.class);
      delete.get(10, TimeUnit.SECONDS);
    }

    assertThat(storedPosterKeys()).isEmpty();
    assertThat(itemResults.findByItem(entityId, ImageEntityType.MOVIE)).isEmpty();
    assertThat(storedArtworkFiles()).isEmpty();
  }

  private ArtworkSources posterArtwork(String key) {
    return ArtworkSources.builder()
        .entityId(entityId)
        .entityType(ImageEntityType.MOVIE)
        .sources(List.of(new TmdbImageSource(ImageType.POSTER, key)))
        .build();
  }

  private void stubImage(String key) {
    wireMock.stubFor(
        get(urlEqualTo(key))
            .willReturn(aResponse().withStatus(200).withBody(createTestImage(600, 900))));
  }

  private List<String> storedPosterKeys() {
    return imageRepository
        .findByEntityIdAndEntityTypeAndImageType(entityId, ImageEntityType.MOVIE, ImageType.POSTER)
        .stream()
        .map(Image::getKey)
        .toList();
  }

  private ItemResult posterResult() {
    return itemResults.findByItem(entityId, ImageEntityType.MOVIE).stream()
        .filter(result -> result.step() == ItemStep.ARTWORK)
        .filter(result -> result.imageType() == ImageType.POSTER)
        .findFirst()
        .orElseThrow();
  }

  private List<Path> storedArtworkFiles() throws IOException {
    var directory = Path.of(imageProperties.storagePath(), "movie", entityId.toString());
    if (Files.notExists(directory)) {
      return List.of();
    }

    try (var files = Files.walk(directory)) {
      return files.filter(Files::isRegularFile).toList();
    }
  }

  private void rejectSucceededResultsWhile(Runnable action) {
    jdbcTemplate.execute(
        """
        CREATE FUNCTION reject_succeeded_item_result() RETURNS trigger AS $$
        BEGIN
          RAISE EXCEPTION 'simulated item result write failure';
        END
        $$ LANGUAGE plpgsql
        """);
    jdbcTemplate.execute(
        """
        CREATE TRIGGER reject_succeeded_item_result
        BEFORE INSERT OR UPDATE ON item_result
        FOR EACH ROW WHEN (NEW.outcome = 'SUCCEEDED' AND NEW.item_id = '%s')
        EXECUTE FUNCTION reject_succeeded_item_result()
        """
            .formatted(entityId));
    try {
      action.run();
    } finally {
      jdbcTemplate.execute("DROP TRIGGER reject_succeeded_item_result ON item_result");
      jdbcTemplate.execute("DROP FUNCTION reject_succeeded_item_result()");
    }
  }
}
