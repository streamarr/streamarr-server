package com.streamarr.server.fixtures;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.ArtworkProperties;
import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.fakes.FakeArtworkRequestRepository;
import com.streamarr.server.fakes.FakeArtworkScheduling;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeItemResultRepository;
import com.streamarr.server.fakes.FakeTmdbHttpService;
import com.streamarr.server.services.ArtworkProgress;
import com.streamarr.server.services.ArtworkRequestService;
import com.streamarr.server.services.ArtworkRun;
import com.streamarr.server.services.ArtworkRunSummary;
import com.streamarr.server.services.ArtworkRunWaiter;
import com.streamarr.server.services.ArtworkStepsRunner;
import com.streamarr.server.services.ImageService;
import com.streamarr.server.services.concurrency.CpuPermits;
import com.streamarr.server.services.concurrency.Sleeper;
import com.streamarr.server.services.metadata.ImageVariantService;
import com.streamarr.server.services.metadata.TmdbImageDownloader;
import java.nio.file.FileSystem;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.Builder;

/**
 * Artwork services wired over in-memory fakes: requests, results and images in fakes that keep the
 * database's generation guards, and scheduled steps that run on the calling thread.
 */
public record ArtworkFixture(
    ArtworkRequestService artworkRequestService,
    ArtworkRunWaiter artworkRunWaiter,
    ArtworkStepsRunner stepsRunner,
    ArtworkProgress progress,
    FakeArtworkScheduling scheduling,
    FakeArtworkRequestRepository requests,
    FakeItemResultRepository itemResults,
    FakeImageRepository imageRepository,
    FileSystem fileSystem) {

  private static final Duration CHECK_INTERVAL = Duration.ofMillis(10);

  /**
   * Unset collaborators default to empty fakes, a downloader with no image, the system clock, and a
   * sleeper that checks recorded results again without waiting in real time.
   */
  @Builder
  private static ArtworkFixture create(
      FakeImageRepository imageRepository,
      TmdbImageDownloader imageDownloader,
      Clock clock,
      FileSystem fileSystem,
      FakeItemResultRepository itemResults,
      Sleeper sleeper) {
    var images = Objects.requireNonNullElseGet(imageRepository, FakeImageRepository::new);
    var downloader = Objects.requireNonNullElseGet(imageDownloader, FakeTmdbHttpService::new);
    var artworkClock = Objects.requireNonNullElseGet(clock, Clock::systemUTC);
    var imageFileSystem =
        Objects.requireNonNullElseGet(fileSystem, () -> Jimfs.newFileSystem(Configuration.unix()));
    var results = Objects.requireNonNullElseGet(itemResults, FakeItemResultRepository::new);
    var resultSleeper = Objects.requireNonNullElse(sleeper, ArtworkFixture::yieldUntilInterrupted);

    results.stampStoredResultsWith(artworkClock);
    var requests = new FakeArtworkRequestRepository(results);
    images.checkArtworkGenerationsWith(requests::isPendingGeneration);
    var imageService =
        new ImageService(
            images,
            new ImageVariantService(),
            new ImageProperties("/data/images"),
            imageFileSystem,
            results);
    var stepsRunner =
        ArtworkStepsRunner.builder()
            .requests(requests)
            .itemResults(results)
            .imageService(imageService)
            .imageDownloader(downloader)
            .cpuPermits(new CpuPermits(Runtime.getRuntime().availableProcessors()))
            .clock(artworkClock)
            .build();
    var scheduling = new FakeArtworkScheduling();
    scheduling.runStepsWith(stepsRunner, requests, artworkClock);
    return new ArtworkFixture(
        new ArtworkRequestService(requests, scheduling, imageService, artworkClock),
        ArtworkRunWaiter.builder()
            .requests(requests)
            .sleeper(resultSleeper)
            .clock(artworkClock)
            .properties(ArtworkProperties.builder().resultCheckInterval(CHECK_INTERVAL).build())
            .build(),
        stepsRunner,
        new ArtworkProgress(requests, results, artworkClock),
        scheduling,
        requests,
        results,
        images,
        imageFileSystem);
  }

  /**
   * Closes the run and waits at most five seconds for its recorded results, as a scan does after
   * its last request.
   */
  public ArtworkRunSummary closeAndAwait(ArtworkRun run) {
    run.close();
    var wait =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return artworkRunWaiter.awaitResults(run);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
              }
            },
            Executors.newVirtualThreadPerTaskExecutor());
    try {
      return wait.get(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException | TimeoutException e) {
      wait.cancel(true);
      throw new IllegalStateException("Artwork run did not finish: " + run.description(), e);
    }
  }

  private static void yieldUntilInterrupted(Duration ignored) throws InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }

    Thread.yield();
  }
}
