package com.streamarr.server.services.library;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.exceptions.LibraryRefreshFailedException;
import com.streamarr.server.exceptions.UnsupportedMediaTypeException;
import com.streamarr.server.repositories.media.ItemResultRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.ArtworkRequestService;
import com.streamarr.server.services.ArtworkRun;
import com.streamarr.server.services.ArtworkRunWaiter;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeasonWithEpisodesRequest;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.concurrency.AdaptiveAdmission;
import com.streamarr.server.services.concurrency.AdmittedTaskReport;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refreshes each movie or series from its provider and records the metadata result for that item.
 * The result carries the refresh's library operation id, so an execution after a restart skips the
 * items that an earlier execution of the operation refreshed. Each media title's task is admitted
 * through the refresh's adaptive limit (ADR 0041). An item failure does not stop the refresh; a
 * result that cannot be recorded fails it and ends admission.
 */
@Slf4j
@Service
@Builder
@RequiredArgsConstructor
public class LibraryRefreshService {

  private final SeriesRepository seriesRepository;
  private final MovieRepository movieRepository;
  private final SeriesService seriesService;
  private final MovieService movieService;
  private final SeriesMetadataProviderResolver seriesMetadataProviderResolver;
  private final MovieMetadataProviderResolver movieMetadataProviderResolver;
  private final ArtworkRequestService artworkRequestService;
  private final ArtworkRunWaiter artworkRunWaiter;
  private final ItemResultRepository itemResults;
  private final Clock clock;
  private final LibraryOperationAdmissions libraryOperationAdmissions;
  private final TransactionTemplate transactionTemplate;

  /**
   * Returns once every item's metadata result is recorded and the primary artwork of the refresh's
   * library operation has recorded results, including artwork that an earlier execution of the
   * operation requested. Person and company artwork continues in the background.
   *
   * @throws LibraryRefreshFailedException if a result was not recorded
   * @throws InterruptedException if the refresh is interrupted; the admitted tasks are interrupted
   *     and joined first
   */
  public void refreshLibrary(Library library, LibraryOperation operation)
      throws InterruptedException {
    var artworkRun =
        artworkRequestService.openLibraryOperationRun(
            "refresh of library '" + library.getName() + "'",
            operation.imageRefreshMode().orElseThrow(),
            operation.id());
    var refreshedItemIds =
        itemResults.findItemIdsWithMetadataResultForLibraryOperation(operation.id());
    try (artworkRun;
        var admission = libraryOperationAdmissions.openRefreshAdmission(library)) {
      var refresh =
          RefreshOperation.builder()
              .library(library)
              .artworkRun(artworkRun)
              .admission(admission)
              .refreshedItemIds(refreshedItemIds)
              .build();
      switch (library.getType()) {
        case SERIES -> refreshSeriesLibrary(refresh);
        case MOVIE -> refreshMovieLibrary(refresh);
        case OTHER -> throw new UnsupportedMediaTypeException(library.getType().name());
      }
    }

    artworkRunWaiter.awaitLibraryOperationResults(artworkRun);
  }

  @Builder
  private record RefreshOperation(
      Library library,
      ArtworkRun artworkRun,
      AdaptiveAdmission admission,
      Set<UUID> refreshedItemIds) {}

  private record MediaTitleToRefresh<T>(T entity, String tmdbId) {}

  @FunctionalInterface
  private interface MediaTitleRefresh<T> {
    ItemOutcome refresh(MediaTitleToRefresh<T> mediaTitle, ArtworkRun taskRun);
  }

  @Builder
  private record AdmittedRefreshes<T>(
      List<MediaTitleToRefresh<T>> mediaTitles,
      MediaTitleRefresh<T> refresh,
      String threadNamePrefix) {}

  private void refreshSeriesLibrary(RefreshOperation operation) throws InterruptedException {
    var library = operation.library();
    forkAdmittedRefreshes(
        operation,
        AdmittedRefreshes.<Series>builder()
            .mediaTitles(
                mediaTitlesToRefresh(
                    operation,
                    seriesRepository.findWithExternalIdsByLibrary_Id(library.getId()),
                    "series"))
            .refresh((mediaTitle, taskRun) -> refreshSeries(mediaTitle, library, taskRun))
            .threadNamePrefix("library-refresh-series-")
            .build());
  }

  private void refreshMovieLibrary(RefreshOperation operation) throws InterruptedException {
    var library = operation.library();
    forkAdmittedRefreshes(
        operation,
        AdmittedRefreshes.<Movie>builder()
            .mediaTitles(
                mediaTitlesToRefresh(
                    operation,
                    movieRepository.findWithExternalIdsByLibrary_Id(library.getId()),
                    "movie"))
            .refresh((mediaTitle, taskRun) -> refreshMovie(mediaTitle, library, taskRun))
            .threadNamePrefix("library-refresh-movie-")
            .build());
  }

  // A media title that an earlier execution refreshed, or that has no TMDB id, takes no admission.
  private <T extends BaseCollectable<?>> List<MediaTitleToRefresh<T>> mediaTitlesToRefresh(
      RefreshOperation operation, List<T> mediaTitles, String kind) {
    var withTmdbIds = new ArrayList<MediaTitleToRefresh<T>>();
    for (var mediaTitle : mediaTitles) {
      if (operation.refreshedItemIds().contains(mediaTitle.getId())) {
        continue;
      }

      var tmdbId = findTmdbId(mediaTitle);
      if (tmdbId.isEmpty()) {
        log.warn("No TMDB ID for {} '{}', skipping refresh", kind, mediaTitle.getTitle());
        continue;
      }

      withTmdbIds.add(new MediaTitleToRefresh<>(mediaTitle, tmdbId.get()));
    }

    return withTmdbIds;
  }

  // Each task keeps its admission until the primary artwork it requested has recorded results.
  private <T> void forkAdmittedRefreshes(RefreshOperation operation, AdmittedRefreshes<T> refreshes)
      throws InterruptedException {
    var failures =
        operation
            .admission()
            .forkAdmittedTasks(
                refreshes.mediaTitles().iterator(),
                mediaTitle -> refreshAdmittedMediaTitle(operation, refreshes.refresh(), mediaTitle),
                SystemWork.newThreadFactoryRunningAsSystemActor(refreshes.threadNamePrefix()));
    throwIfAnyRefreshTaskFailed(operation.library(), failures);
  }

  private <T> AdmittedTaskReport refreshAdmittedMediaTitle(
      RefreshOperation operation, MediaTitleRefresh<T> refresh, MediaTitleToRefresh<T> mediaTitle)
      throws InterruptedException {
    var taskRun = operation.artworkRun().openTaskRun();
    ItemOutcome outcome;
    try (taskRun) {
      outcome = refresh.refresh(mediaTitle, taskRun);
    }

    if (!artworkRunWaiter.tryAwaitTaskResults(taskRun)) {
      return AdmittedTaskReport.DROP;
    }

    return AdmissionReports.forRefresh(outcome);
  }

  private ItemOutcome refreshSeries(
      MediaTitleToRefresh<Series> mediaTitle, Library library, ArtworkRun artworkRun) {
    var series = mediaTitle.entity();
    var tmdbId = mediaTitle.tmdbId();
    var result =
        metadataResult(series.getId(), ImageEntityType.SERIES, artworkRun)
            .attemptedAt(clock.instant());
    var outcome = refreshSeriesMetadata(series, tmdbId, library, artworkRun);

    return saveMetadataResult(result, outcome);
  }

  private ItemOutcome refreshSeriesMetadata(
      Series series, String tmdbId, Library library, ArtworkRun artworkRun) {
    try {
      return switch (seriesMetadataProviderResolver.getMetadata(
          searchResult(tmdbId, series.getTitle()), library)) {
        case MetadataFetchOutcome.Found(var metadataResult) -> {
          var refreshedSeries =
              seriesService.refreshSeriesMetadata(series, metadataResult, artworkRun);
          yield refreshSeasons(refreshedSeries, tmdbId, library, artworkRun);
        }
        case MetadataFetchOutcome.NotFound<?> _ -> {
          log.error("TMDB no longer has series '{}' TMDB id '{}'", series.getTitle(), tmdbId);
          yield new ItemOutcome.Unavailable();
        }
        case MetadataFetchOutcome.Failed<?> failed -> {
          log.error(
              "Failed to fetch metadata for series '{}' TMDB id '{}'", series.getTitle(), tmdbId);
          yield failed.toItemOutcome();
        }
      };
    } catch (RuntimeException ex) {
      log.error("Failed to refresh series '{}' TMDB id '{}'", series.getTitle(), tmdbId, ex);
      return ItemOutcome.Failed.of(ItemFailureReason.TEMPORARY, ex);
    }
  }

  private ItemOutcome refreshSeasons(
      Series series, String tmdbId, Library library, ArtworkRun artworkRun) {
    var seasonList = seriesMetadataProviderResolver.getAvailableSeasonNumbers(library, tmdbId);
    if (!(seasonList instanceof MetadataFetchOutcome.Found(var seasonNumbers))) {
      return seasonListFailure(tmdbId, seasonList);
    }

    var seasonFailures = new ArrayList<ItemOutcome.Failed>();
    for (var seasonNumber : seasonNumbers) {
      switch (seriesMetadataProviderResolver.getSeasonDetails(library, tmdbId, seasonNumber)) {
        case MetadataFetchOutcome.Found(var seasonDetails) ->
            seriesService.refreshSeasonWithEpisodes(
                SeasonWithEpisodesRequest.builder()
                    .series(series)
                    .details(seasonDetails)
                    .library(library)
                    .artworkRun(artworkRun)
                    .build());
        case MetadataFetchOutcome.NotFound<?> _ ->
            seasonFailures.add(seasonNotFound(tmdbId, seasonNumber));
        case MetadataFetchOutcome.Failed<?> failed ->
            seasonFailures.add(seasonFailure(tmdbId, seasonNumber, failed));
      }
    }

    if (seasonFailures.isEmpty()) {
      return new ItemOutcome.Succeeded();
    }

    return seasonFailures.getFirst();
  }

  private static ItemOutcome seasonListFailure(
      String tmdbId, MetadataFetchOutcome<List<Integer>> seasonList) {
    if (!(seasonList instanceof MetadataFetchOutcome.Failed<List<Integer>> failed)) {
      log.warn("TMDB has no season list for series TMDB id '{}'", tmdbId);
      return new ItemOutcome.Unavailable();
    }

    log.warn("Failed to fetch the season list for series TMDB id '{}'", tmdbId);
    var failure = failed.toItemOutcome();
    return new ItemOutcome.Failed(failure.reason(), "Season list: " + failure.detail());
  }

  private static ItemOutcome.Failed seasonNotFound(String tmdbId, int seasonNumber) {
    log.warn("TMDB has no season {} for series TMDB id '{}'", seasonNumber, tmdbId);
    return new ItemOutcome.Failed(
        ItemFailureReason.TEMPORARY, "Season %d: not found".formatted(seasonNumber));
  }

  private static ItemOutcome.Failed seasonFailure(
      String tmdbId, int seasonNumber, MetadataFetchOutcome.Failed<?> failed) {
    log.warn("Failed to fetch season {} for series TMDB id '{}'", seasonNumber, tmdbId);
    var failure = failed.toItemOutcome();
    return new ItemOutcome.Failed(
        failure.reason(), "Season %d: %s".formatted(seasonNumber, failure.detail()));
  }

  private ItemOutcome refreshMovie(
      MediaTitleToRefresh<Movie> mediaTitle, Library library, ArtworkRun artworkRun) {
    var movie = mediaTitle.entity();
    var tmdbId = mediaTitle.tmdbId();
    var result =
        metadataResult(movie.getId(), ImageEntityType.MOVIE, artworkRun)
            .attemptedAt(clock.instant());
    MetadataFetchOutcome<MetadataResult<Movie>> fetched;
    try {
      fetched =
          movieMetadataProviderResolver.getMetadata(
              searchResult(tmdbId, movie.getTitle()), library);
    } catch (RuntimeException ex) {
      log.error(
          "Failed to fetch metadata for movie '{}' TMDB id '{}'", movie.getTitle(), tmdbId, ex);
      return saveMetadataResult(result, ItemOutcome.Failed.of(ItemFailureReason.TEMPORARY, ex));
    }

    return switch (fetched) {
      case MetadataFetchOutcome.Found(var metadataResult) ->
          saveRefreshedMovie(movie, metadataResult, artworkRun, result);
      case MetadataFetchOutcome.NotFound<?> _ -> {
        log.error("TMDB no longer has movie '{}' TMDB id '{}'", movie.getTitle(), tmdbId);
        yield saveMetadataResult(result, new ItemOutcome.Unavailable());
      }
      case MetadataFetchOutcome.Failed<?> failed -> {
        log.error("Failed to fetch metadata for movie '{}' TMDB id '{}'", movie.getTitle(), tmdbId);
        yield saveMetadataResult(result, failed.toItemOutcome());
      }
    };
  }

  // The movie, its artwork request and its metadata result commit together, so an execution after
  // a restart never refreshes a movie whose result is missing, or skips one whose refresh is lost.
  private ItemOutcome saveRefreshedMovie(
      Movie movie,
      MetadataResult<Movie> metadataResult,
      ArtworkRun artworkRun,
      ItemResult.ItemResultBuilder result) {
    try {
      transactionTemplate.executeWithoutResult(
          _ -> {
            movieService.refreshMovieMetadata(movie, metadataResult, artworkRun);
            itemResults.overwrite(result.outcome(new ItemOutcome.Succeeded()).build());
          });
      return new ItemOutcome.Succeeded();
    } catch (RuntimeException ex) {
      log.error("Failed to refresh movie '{}'", movie.getTitle(), ex);
      return saveMetadataResult(result, ItemOutcome.Failed.of(ItemFailureReason.TEMPORARY, ex));
    }
  }

  // A failure while the task is interrupted comes from the shutdown, not from the title. The title
  // keeps no result for the operation, so the next execution refreshes it.
  private ItemOutcome saveMetadataResult(ItemResult.ItemResultBuilder result, ItemOutcome outcome) {
    if (outcome instanceof ItemOutcome.Failed && Thread.currentThread().isInterrupted()) {
      return outcome;
    }

    itemResults.trySave(result.outcome(outcome).build());
    return outcome;
  }

  private static RemoteSearchResult searchResult(String tmdbId, String title) {
    return RemoteSearchResult.builder()
        .externalId(tmdbId)
        .externalSourceType(ExternalSourceType.TMDB)
        .title(title)
        .build();
  }

  private static ItemResult.ItemResultBuilder metadataResult(
      UUID itemId, ImageEntityType itemType, ArtworkRun artworkRun) {
    return ItemResult.builder()
        .itemId(itemId)
        .itemType(itemType)
        .step(ItemStep.METADATA)
        .libraryOperationId(artworkRun.libraryOperationId());
  }

  private static void throwIfAnyRefreshTaskFailed(Library library, List<Throwable> failures) {
    if (failures.isEmpty()) {
      return;
    }

    var failure = new LibraryRefreshFailedException(library.getName(), failures.getFirst());
    failures.stream().skip(1).forEach(failure::addSuppressed);
    throw failure;
  }

  private Optional<String> findTmdbId(BaseCollectable<?> entity) {
    return entity.getExternalIds().stream()
        .filter(eid -> eid.getExternalSourceType() == ExternalSourceType.TMDB)
        .map(ExternalIdentifier::getExternalId)
        .findFirst();
  }
}
