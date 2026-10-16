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
import com.streamarr.server.domain.media.Season;
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
import com.streamarr.server.services.metadata.series.SeasonDetails;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refreshes each movie or series from its provider and records the metadata result for that item.
 * The result carries the refresh's library operation id, so an execution after a restart skips the
 * items that an earlier execution of the operation refreshed. A series also saves a result for each
 * season it refreshes, so an execution after a restart resumes the series at its first season
 * without one. Each media title's task is admitted through the refresh's adaptive limit (ADR 0041).
 * An item failure does not stop the refresh; a result that cannot be recorded fails it and ends
 * admission.
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
   * Returns once every admitted item's metadata result is recorded and the primary artwork of the
   * refresh's library operation has recorded results, including artwork that an earlier execution
   * of the operation requested. The refresh admits no further item once the admission end is
   * reached. Person and company artwork continues in the background.
   *
   * @throws LibraryRefreshFailedException if a result was not recorded
   * @throws InterruptedException if the refresh is interrupted; the admitted tasks are interrupted
   *     and joined first
   */
  public void refreshLibrary(Library library, LibraryOperation operation, AdmissionEnd admissionEnd)
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
              .admissionEnd(admissionEnd)
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
      Set<UUID> refreshedItemIds,
      AdmissionEnd admissionEnd) {}

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
            .refresh((mediaTitle, taskRun) -> refreshSeries(mediaTitle, operation, taskRun))
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
    return mediaTitles.stream()
        .filter(mediaTitle -> !operation.refreshedItemIds().contains(mediaTitle.getId()))
        .flatMap(mediaTitle -> mediaTitleWithTmdbId(mediaTitle, kind).stream())
        .toList();
  }

  private <T extends BaseCollectable<?>> Optional<MediaTitleToRefresh<T>> mediaTitleWithTmdbId(
      T mediaTitle, String kind) {
    var tmdbId = findTmdbId(mediaTitle);
    if (tmdbId.isEmpty()) {
      log.warn("No TMDB ID for {} '{}', skipping refresh", kind, mediaTitle.getTitle());
      return Optional.empty();
    }

    return Optional.of(new MediaTitleToRefresh<>(mediaTitle, tmdbId.get()));
  }

  // Each task keeps its admission until the primary artwork it requested has recorded results.
  private <T> void forkAdmittedRefreshes(RefreshOperation operation, AdmittedRefreshes<T> refreshes)
      throws InterruptedException {
    var failures =
        operation
            .admission()
            .forkAdmittedTasks(
                operation.admissionEnd().untilReached(refreshes.mediaTitles().iterator()),
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

  // A series' metadata result commits with its last season, so an execution after a restart skips
  // the series only when every season is refreshed, and resumes at the first season without a
  // result for the operation.
  private ItemOutcome refreshSeries(
      MediaTitleToRefresh<Series> mediaTitle, RefreshOperation operation, ArtworkRun artworkRun) {
    var series = mediaTitle.entity();
    var refresh =
        SeriesToRefresh.builder()
            .series(series)
            .tmdbId(mediaTitle.tmdbId())
            .library(operation.library())
            .artworkRun(artworkRun)
            .refreshedItemIds(operation.refreshedItemIds())
            .result(
                metadataResult(series.getId(), ImageEntityType.SERIES, artworkRun)
                    .attemptedAt(clock.instant()))
            .build();

    return switch (refreshSeriesMetadata(refresh)) {
      case SeriesOutcome.SavedWithLastSeason(var outcome) -> outcome;
      case SeriesOutcome.Unsaved(var outcome) -> saveMetadataResult(refresh.result(), outcome);
    };
  }

  @Builder(toBuilder = true)
  private record SeriesToRefresh(
      Series series,
      String tmdbId,
      Library library,
      ArtworkRun artworkRun,
      Set<UUID> refreshedItemIds,
      ItemResult.ItemResultBuilder result) {}

  private sealed interface SeriesOutcome {

    record SavedWithLastSeason(ItemOutcome outcome) implements SeriesOutcome {}

    record Unsaved(ItemOutcome outcome) implements SeriesOutcome {}
  }

  private SeriesOutcome refreshSeriesMetadata(SeriesToRefresh refresh) {
    var series = refresh.series();
    var tmdbId = refresh.tmdbId();
    try {
      return switch (seriesMetadataProviderResolver.getMetadata(
          searchResult(tmdbId, series.getTitle()), refresh.library())) {
        case MetadataFetchOutcome.Found(var metadataResult) -> {
          var refreshedSeries =
              seriesService.refreshSeriesMetadata(series, metadataResult, refresh.artworkRun());
          yield refreshSeasons(refresh.toBuilder().series(refreshedSeries).build());
        }
        case MetadataFetchOutcome.NotFound<?> _ -> {
          log.error("TMDB no longer has series '{}' TMDB id '{}'", series.getTitle(), tmdbId);
          yield new SeriesOutcome.Unsaved(new ItemOutcome.Unavailable());
        }
        case MetadataFetchOutcome.Failed<?> failed -> {
          log.error(
              "Failed to fetch metadata for series '{}' TMDB id '{}'", series.getTitle(), tmdbId);
          yield new SeriesOutcome.Unsaved(failed.toItemOutcome());
        }
      };
    } catch (RuntimeException ex) {
      log.error("Failed to refresh series '{}' TMDB id '{}'", series.getTitle(), tmdbId, ex);
      return new SeriesOutcome.Unsaved(ItemOutcome.Failed.of(ItemFailureReason.TEMPORARY, ex));
    }
  }

  private SeriesOutcome refreshSeasons(SeriesToRefresh refresh) {
    var tmdbId = refresh.tmdbId();
    var seasonList =
        seriesMetadataProviderResolver.getAvailableSeasonNumbers(refresh.library(), tmdbId);
    if (!(seasonList instanceof MetadataFetchOutcome.Found(var seasonNumbers))) {
      return new SeriesOutcome.Unsaved(seasonListFailure(tmdbId, seasonList));
    }

    var seasonsToRefresh = seasonsWithoutResult(refresh, seasonNumbers);
    if (seasonsToRefresh.isEmpty()) {
      return new SeriesOutcome.Unsaved(new ItemOutcome.Succeeded());
    }

    var seasonFailures = new ArrayList<ItemOutcome.Failed>();
    for (var seasonNumber : seasonsToRefresh.subList(0, seasonsToRefresh.size() - 1)) {
      refreshSeason(refresh, seasonNumber, List.of()).ifPresent(seasonFailures::add);
    }

    var seriesOutcome = firstFailureOrSuccess(seasonFailures);
    var lastSeasonFailure =
        refreshSeason(
            refresh,
            seasonsToRefresh.getLast(),
            List.of(refresh.result().outcome(seriesOutcome).build()));
    if (lastSeasonFailure.isEmpty()) {
      return new SeriesOutcome.SavedWithLastSeason(seriesOutcome);
    }

    seasonFailures.add(lastSeasonFailure.get());
    return new SeriesOutcome.Unsaved(seasonFailures.getFirst());
  }

  private List<Integer> seasonsWithoutResult(SeriesToRefresh refresh, List<Integer> seasonNumbers) {
    var refreshedSeasonNumbers =
        seriesService.findSeasons(refresh.series().getId()).stream()
            .filter(season -> refresh.refreshedItemIds().contains(season.getId()))
            .map(Season::getSeasonNumber)
            .collect(Collectors.toSet());
    return seasonNumbers.stream()
        .filter(seasonNumber -> !refreshedSeasonNumbers.contains(seasonNumber))
        .toList();
  }

  private static ItemOutcome firstFailureOrSuccess(List<ItemOutcome.Failed> failures) {
    if (failures.isEmpty()) {
      return new ItemOutcome.Succeeded();
    }

    return failures.getFirst();
  }

  // A shutdown stops the series at the season it reaches, so no later season is fetched.
  private Optional<ItemOutcome.Failed> refreshSeason(
      SeriesToRefresh refresh, int seasonNumber, List<ItemResult> resultsSavedWithSeason) {
    if (Thread.currentThread().isInterrupted()) {
      return Optional.of(
          new ItemOutcome.Failed(
              ItemFailureReason.TEMPORARY, "Season %d: interrupted".formatted(seasonNumber)));
    }

    var tmdbId = refresh.tmdbId();
    var attemptedAt = clock.instant();
    return switch (seriesMetadataProviderResolver.getSeasonDetails(
        refresh.library(), tmdbId, seasonNumber)) {
      case MetadataFetchOutcome.Found(var seasonDetails) -> {
        saveSeason(
            refresh,
            SeasonToSave.builder()
                .details(seasonDetails)
                .attemptedAt(attemptedAt)
                .resultsSavedWithSeason(resultsSavedWithSeason)
                .build());
        yield Optional.empty();
      }
      case MetadataFetchOutcome.NotFound<?> _ -> Optional.of(seasonNotFound(tmdbId, seasonNumber));
      case MetadataFetchOutcome.Failed<?> failed ->
          Optional.of(seasonFailure(tmdbId, seasonNumber, failed));
    };
  }

  @Builder
  private record SeasonToSave(
      SeasonDetails details, Instant attemptedAt, List<ItemResult> resultsSavedWithSeason) {}

  // The season, its episodes, their artwork requests and the season's metadata result commit
  // together in a short transaction of their own.
  private void saveSeason(SeriesToRefresh refresh, SeasonToSave season) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var savedSeason =
              seriesService.refreshSeasonWithEpisodes(
                  SeasonWithEpisodesRequest.builder()
                      .series(refresh.series())
                      .details(season.details())
                      .library(refresh.library())
                      .artworkRun(refresh.artworkRun())
                      .build());
          itemResults.overwrite(
              metadataResult(savedSeason.getId(), ImageEntityType.SEASON, refresh.artworkRun())
                  .attemptedAt(season.attemptedAt())
                  .outcome(new ItemOutcome.Succeeded())
                  .build());
          season.resultsSavedWithSeason().forEach(itemResults::overwrite);
        });
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
