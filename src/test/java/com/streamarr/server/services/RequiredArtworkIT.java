package com.streamarr.server.services;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.streamarr.server.fakes.TestImages.createTestImage;
import static com.streamarr.server.support.CpuPermitWaiters.backgroundWaiters;
import static com.streamarr.server.support.CpuPermitWaiters.primaryWaiters;
import static com.streamarr.server.support.HeldResponses.held;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.streamarr.server.AbstractWireMockIntegrationTest;
import com.streamarr.server.config.ArtworkPrioritySchedulers;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.ItemResultRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.concurrency.CpuPermits;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.support.ArtworkSchedulerStarter;
import com.streamarr.server.support.BoundedTask;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Required Artwork Integration Tests")
class RequiredArtworkIT extends AbstractWireMockIntegrationTest {

  private static final long BOUND_SECONDS = 10;

  @Autowired private ArtworkRequestService artworkRequestService;
  @Autowired private ArtworkRunWaiter artworkRunWaiter;
  @Autowired private ItemResultRepository itemResults;
  @Autowired private MovieService movieService;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PersonRepository personRepository;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private ArtworkPrioritySchedulers artworkPrioritySchedulers;
  @Autowired private ApplicationContext applicationContext;
  @Autowired private CpuPermits cpuPermits;

  private Library library;

  // An earlier test class can leave a step that becomes due once a test starts its own schedulers,
  // which would count the step's completion as the test's own. Stopping the context's schedulers
  // first lets a running step finish before its rows are deleted.
  @BeforeEach
  void setUp() {
    artworkPrioritySchedulers.stop();
    deleteArtworkRequestsAndTasks();
    artworkPrioritySchedulers.start();
    wireMock.resetAll();
    wireMock.stubFor(
        get(urlPathEqualTo("/poster.jpg"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/jpeg")
                    .withBody(createTestImage(600, 900))));
    library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
  }

  // Background steps that a test leaves pending would otherwise run during later tests.
  @AfterEach
  void tearDown() {
    deleteArtworkRequestsAndTasks();
  }

  private void deleteArtworkRequestsAndTasks() {
    jdbcTemplate.update("DELETE FROM artwork_request");
    jdbcTemplate.update("DELETE FROM " + ArtworkSchedulerStarter.PRIMARY_TABLE);
    jdbcTemplate.update("DELETE FROM " + ArtworkSchedulerStarter.BACKGROUND_TABLE);
  }

  @Test
  @DisplayName("Should commit image records before the run reports required artwork finished")
  void shouldCommitImageRecordsBeforeRunReportsRequiredArtworkFinished() throws Exception {
    var artworkRun =
        artworkRequestService.openLibraryOperationRun(
            "scan", ImageRefreshMode.PRESERVE, UUID.randomUUID());

    var movie = movieService.createMovieWithAssociations(movieMetadata(), mediaFile(), artworkRun);
    var summary = closeAndAwait(artworkRun);

    assertThat(summary.counts())
        .isEqualTo(FinishedImageCounts.builder().saved(1).unavailable(1).build());
    assertThat(committedImageRows(movie.getId())).isEqualTo(ImageSize.values().length);
  }

  @Test
  @DisplayName("Should withdraw required artwork when the owning transaction rolls back")
  void shouldWithdrawRequiredArtworkWhenOwningTransactionRollsBack() throws Exception {
    var artworkRun =
        artworkRequestService.openLibraryOperationRun(
            "scan", ImageRefreshMode.PRESERVE, UUID.randomUUID());
    var mediaFile = mediaFile();

    var movie =
        transactionTemplate.execute(
            status -> {
              status.setRollbackOnly();
              return movieService.createMovieWithAssociations(
                  movieMetadata(), mediaFile, artworkRun);
            });
    var summary = closeAndAwait(artworkRun);

    assertThat(summary.counts()).isEqualTo(FinishedImageCounts.builder().build());
    assertThat(committedImageRows(movie.getId())).isZero();
    assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo("/poster.jpg")))).isEmpty();
  }

  @Test
  @DisplayName("Should name the System actor as creator when required artwork saves images")
  void shouldNameSystemActorAsCreatorWhenRequiredArtworkSavesImages() throws Exception {
    var artworkRun =
        artworkRequestService.openLibraryOperationRun(
            "scan", ImageRefreshMode.PRESERVE, UUID.randomUUID());

    var movie = movieService.createMovieWithAssociations(movieMetadata(), mediaFile(), artworkRun);
    closeAndAwait(artworkRun);

    assertThat(imageCreators(movie.getId()))
        .hasSize(ImageSize.values().length)
        .containsOnly(SystemActor.ID);
  }

  @Test
  @DisplayName("Should name the System actor as creator when background artwork saves images")
  void shouldNameSystemActorAsCreatorWhenBackgroundArtworkSavesImages() throws Exception {
    var person =
        personRepository.saveAndFlush(
            Person.builder().name("Artwork Person").sourceId(UUID.randomUUID().toString()).build());
    var artwork =
        ArtworkSources.builder()
            .targetId(person.getId())
            .targetType(ImageEntityType.PERSON)
            .sources(List.of(new TmdbImageSource(ImageType.PROFILE, "/poster.jpg")))
            .build();

    var completed = new CompletableFuture<ExecutionComplete>();
    artworkPrioritySchedulers.stop();
    var backgroundScheduler =
        ArtworkSchedulerStarter.fromContext(applicationContext)
            .start(ArtworkPriority.BACKGROUND, completedInto(completed));
    try {
      transactionTemplate.executeWithoutResult(
          _ -> artworkRequestService.requestBackgroundArtwork(artwork, ImageRefreshMode.PRESERVE));

      completed.get(BOUND_SECONDS, TimeUnit.SECONDS);
    } finally {
      backgroundScheduler.stop();
      artworkPrioritySchedulers.start();
    }

    assertThat(itemResults.findByItem(person.getId(), ImageEntityType.PERSON))
        .extracting(ItemResult::outcome)
        .containsExactly(new ItemOutcome.Succeeded());
    assertThat(imageCreators(person.getId()))
        .hasSize(ImageSize.values().length)
        .containsOnly(SystemActor.ID);
  }

  @Test
  @DisplayName(
      "Should save the poster while a backlog of person photos and company logos downloads")
  void shouldSaveThePosterWhileABacklogOfPersonPhotosAndCompanyLogosDownloads() throws Exception {
    wireMock.stubFor(
        held(
            get(urlPathMatching("/backlog-.*"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "image/jpeg")
                        .withBody(createTestImage(600, 900)))));
    var backlog = new ArrayList<ArtworkTarget>();
    for (var index = 0; index < 6; index++) {
      var person =
          personRepository.saveAndFlush(
              Person.builder()
                  .name("Backlog Person")
                  .sourceId(UUID.randomUUID().toString())
                  .build());
      backlog.add(new ArtworkTarget(person.getId(), ImageEntityType.PERSON));
    }

    for (var index = 0; index < 2; index++) {
      var company =
          companyRepository.saveAndFlush(
              Company.builder()
                  .name("Backlog Studio")
                  .sourceId(UUID.randomUUID().toString())
                  .build());
      backlog.add(new ArtworkTarget(company.getId(), ImageEntityType.COMPANY));
    }

    transactionTemplate.executeWithoutResult(
        _ -> {
          for (var index = 0; index < backlog.size(); index++) {
            requestBacklogArtwork(backlog.get(index), index);
          }
        });
    heldResponses.awaitHeldResponses(1, Duration.ofSeconds(BOUND_SECONDS));
    var artworkRun =
        artworkRequestService.openLibraryOperationRun(
            "scan", ImageRefreshMode.PRESERVE, UUID.randomUUID());

    movieService.createMovieWithAssociations(movieMetadata(), mediaFile(), artworkRun);
    var summary = closeAndAwait(artworkRun);

    assertThat(summary.counts())
        .isEqualTo(FinishedImageCounts.builder().saved(1).unavailable(1).build());
    assertThat(backlog)
        .allSatisfy(
            target ->
                assertThat(itemResults.findByItem(target.targetId(), target.targetType()))
                    .isEmpty());
  }

  @Test
  @DisplayName(
      "Should give a freed CPU permit to the poster when person photos waited for one before it")
  void shouldGiveAFreedCpuPermitToThePosterWhenPersonPhotosWaitedForOneBeforeIt() throws Exception {
    wireMock.stubFor(
        get(urlPathMatching("/backlog-.*"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/jpeg")
                    .withBody(createTestImage(2000, 3000))));
    var backlog = List.of(savePerson(), savePerson());
    var completions = new CopyOnWriteArrayList<String>();
    var releases = new ArrayList<CountDownLatch>();
    var holders = new ArrayList<BoundedTask<Void>>();
    artworkPrioritySchedulers.stop();
    var starter = ArtworkSchedulerStarter.fromContext(applicationContext);
    var primaryScheduler = starter.start(ArtworkPriority.PRIMARY, completedInto(completions));
    var backgroundScheduler = starter.start(ArtworkPriority.BACKGROUND, completedInto(completions));
    try {
      for (var index = 0; index < Runtime.getRuntime().availableProcessors(); index++) {
        var release = new CountDownLatch(1);
        releases.add(release);
        holders.add(holdCpuPermit(release));
      }

      transactionTemplate.executeWithoutResult(
          _ -> {
            for (var index = 0; index < backlog.size(); index++) {
              requestBacklogArtwork(backlog.get(index), index);
            }
          });
      await().atMost(Duration.ofSeconds(BOUND_SECONDS)).until(() -> backgroundWaiters() == 2);
      var artworkRun =
          artworkRequestService.openLibraryOperationRun(
              "scan", ImageRefreshMode.PRESERVE, UUID.randomUUID());
      var movie =
          movieService.createMovieWithAssociations(movieMetadata(), mediaFile(), artworkRun);
      await().atMost(Duration.ofSeconds(BOUND_SECONDS)).until(() -> primaryWaiters() == 1);

      releases.getFirst().countDown();
      closeAndAwait(artworkRun);
      releases.forEach(CountDownLatch::countDown);
      await()
          .atMost(Duration.ofSeconds(BOUND_SECONDS))
          .until(() -> completions.size() == backlog.size() + 1);

      assertThat(completions.getFirst())
          .isEqualTo(
              ArtworkStepsTask.instanceId(new ArtworkTarget(movie.getId(), ImageEntityType.MOVIE)));
    } finally {
      releases.forEach(CountDownLatch::countDown);
      for (var holder : holders) {
        holder.await(Duration.ofSeconds(BOUND_SECONDS));
      }

      primaryScheduler.stop();
      backgroundScheduler.stop();
      artworkPrioritySchedulers.start();
    }
  }

  private BoundedTask<Void> holdCpuPermit(CountDownLatch release) throws InterruptedException {
    var held = new CountDownLatch(1);
    var holder =
        BoundedTask.start(
            () -> {
              cpuPermits.runWithPermit(
                  CpuPermits.Priority.PRIMARY,
                  () -> {
                    held.countDown();
                    release.await();
                    return null;
                  });
            });
    assertThat(held.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
    return holder;
  }

  private ArtworkTarget savePerson() {
    var person =
        personRepository.saveAndFlush(
            Person.builder().name("Backlog Person").sourceId(UUID.randomUUID().toString()).build());
    return new ArtworkTarget(person.getId(), ImageEntityType.PERSON);
  }

  private void requestBacklogArtwork(ArtworkTarget target, int index) {
    var imageType = ImageType.PROFILE;
    if (target.targetType() == ImageEntityType.COMPANY) {
      imageType = ImageType.LOGO;
    }

    artworkRequestService.requestBackgroundArtwork(
        ArtworkSources.builder()
            .targetId(target.targetId())
            .targetType(target.targetType())
            .sources(List.of(new TmdbImageSource(imageType, "/backlog-" + index + ".jpg")))
            .build(),
        ImageRefreshMode.PRESERVE);
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

  private static AbstractSchedulerListener completedInto(List<String> completedInstanceIds) {
    return new AbstractSchedulerListener() {
      @Override
      public void onExecutionComplete(ExecutionComplete complete) {
        completedInstanceIds.add(complete.getExecution().taskInstance.getId());
      }
    };
  }

  private ArtworkRunSummary closeAndAwait(ArtworkRun artworkRun) throws Exception {
    artworkRun.close();
    try (var wait =
        BoundedTask.start(() -> artworkRunWaiter.awaitLibraryOperationResults(artworkRun))) {
      return wait.await(Duration.ofSeconds(BOUND_SECONDS));
    }
  }

  private MetadataResult<Movie> movieMetadata() {
    return new MetadataResult<>(
        Movie.builder().title("Artwork Movie").titleSort("artwork movie").library(library).build(),
        List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")),
        Map.of(),
        Map.of());
  }

  private MediaFile mediaFile() {
    return mediaFileRepository.save(
        MediaFile.builder()
            .libraryId(library.getId())
            .status(MediaFileStatus.UNMATCHED)
            .filename("artwork.mkv")
            .filepathUri("file:///library/" + UUID.randomUUID() + "/artwork.mkv")
            .build());
  }

  private List<UUID> imageCreators(UUID entityId) {
    return jdbcTemplate.queryForList(
        "SELECT created_by FROM image WHERE entity_id = ?", UUID.class, entityId);
  }

  private int committedImageRows(UUID entityId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM image WHERE entity_id = ?", Integer.class, entityId);
  }
}
