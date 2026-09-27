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
import com.streamarr.server.services.ArtworkRun;
import com.streamarr.server.services.ArtworkService;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeasonWithEpisodesRequest;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.library.admission.AdmissionRuntime;
import com.streamarr.server.services.library.admission.FileAdmission;
import com.streamarr.server.services.library.admission.StagedTask;
import com.streamarr.server.services.library.admission.TaskOutcome;
import com.streamarr.server.services.library.admission.UnboundedFileAdmission;
import com.streamarr.server.services.library.admission.Workload;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Refreshes each movie or series from its provider and records the metadata result for that item.
 * An item failure does not stop the refresh; a result that cannot be recorded fails it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LibraryRefreshService {

  private final SeriesRepository seriesRepository;
  private final MovieRepository movieRepository;
  private final SeriesService seriesService;
  private final MovieService movieService;
  private final SeriesMetadataProviderResolver seriesMetadataProviderResolver;
  private final MovieMetadataProviderResolver movieMetadataProviderResolver;
  private final ArtworkService artworkService;
  private final ItemResultRepository itemResults;
  private final Clock clock;
  private FileAdmission fileAdmission = new UnboundedFileAdmission(new AdmissionRuntime());

  /** Throwaway benchmark seam: the strategy bean selected by {@code poc.admission}. */
  @Autowired
  void useFileAdmission(FileAdmission fileAdmission) {
    this.fileAdmission = fileAdmission;
  }

  public void refreshLibrary(Library library) {
    refreshLibrary(library, ImageRefreshMode.PRESERVE);
  }

  public void refreshLibrary(Library library, ImageRefreshMode imageRefreshMode) {
    try (var artworkRun =
        artworkService.openRun(
            "refresh of library '" + library.getName() + "'", imageRefreshMode)) {
      switch (library.getType()) {
        case SERIES -> refreshSeriesLibrary(library, artworkRun);
        case MOVIE -> refreshMovieLibrary(library, artworkRun);
        case OTHER -> throw new UnsupportedMediaTypeException(library.getType().name());
      }
    }
  }

  private void refreshSeriesLibrary(Library library, ArtworkRun artworkRun) {
    var seriesList = seriesRepository.findWithExternalIdsByLibrary_Id(library.getId());
    var tasks = new ArrayList<Future<?>>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var series : seriesList) {
        var tmdbId = findTmdbId(series);
        if (tmdbId.isEmpty()) {
          log.warn("No TMDB ID for series '{}', skipping refresh", series.getTitle());
          continue;
        }
        var id = tmdbId.get();
        tasks.add(executor.submit(() -> refreshSeries(series, id, library, artworkRun)));
      }
    }

    throwIfAnyRefreshTaskFailed(library, tasks);
  }

  private void refreshSeries(Series series, String tmdbId, Library library, ArtworkRun artworkRun) {
    var attemptedAt = clock.instant();
    var outcome = refreshSeriesMetadata(series, tmdbId, library, artworkRun);

    itemResults.trySave(
        metadataResult(series.getId(), ImageEntityType.SERIES)
            .outcome(outcome)
            .attemptedAt(attemptedAt)
            .build());
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

  private void refreshMovieLibrary(Library library, ArtworkRun artworkRun) {
    var movies = movieRepository.findWithExternalIdsByLibrary_Id(library.getId());
    var targets =
        movies.stream()
            .<MovieRefresh>mapMulti(
                (movie, admit) -> {
                  var tmdbId = findTmdbId(movie);
                  if (tmdbId.isEmpty()) {
                    log.warn("No TMDB ID for movie '{}', skipping refresh", movie.getTitle());
                    return;
                  }

                  admit.accept(new MovieRefresh(movie, tmdbId.get()));
                });

    List<Throwable> failures;
    try {
      failures =
          fileAdmission.processAll(
              Workload.REFRESH, targets, new MovieRefreshTask(library, artworkRun));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new LibraryRefreshFailedException(library.getName(), exception);
    }

    throwIfAnyRefreshFailed(library, failures);
  }

  private record MovieRefresh(Movie movie, String tmdbId) {}

  /** A refresh target after the remote stage: the provider outcome, or what the call threw. */
  private record FetchedMovie(
      MovieRefresh target,
      Instant attemptedAt,
      MetadataFetchOutcome<MetadataResult<Movie>> details,
      RuntimeException thrown) {}

  /**
   * One movie of a refresh. {@link #run} is the original per-movie path; the stages split it into
   * the remote details fetch and the database refresh. There is no database stage before the fetch:
   * the targets were loaded up front.
   */
  private final class MovieRefreshTask
      implements StagedTask<MovieRefresh, MovieRefresh, FetchedMovie> {

    private final Library library;
    private final ArtworkRun artworkRun;

    private MovieRefreshTask(Library library, ArtworkRun artworkRun) {
      this.library = library;
      this.artworkRun = artworkRun;
    }

    @Override
    public TaskOutcome run(MovieRefresh target) {
      refreshMovie(target.movie(), target.tmdbId(), library, artworkRun);
      return TaskOutcome.WORKED;
    }

    @Override
    public Optional<MovieRefresh> register(MovieRefresh target) {
      return Optional.of(target);
    }

    @Override
    public FetchedMovie identify(MovieRefresh target) {
      var attemptedAt = clock.instant();
      try {
        return new FetchedMovie(
            target,
            attemptedAt,
            movieMetadataProviderResolver.getMetadata(
                searchResult(target.tmdbId(), target.movie().getTitle()), library),
            null);
      } catch (RuntimeException ex) {
        return new FetchedMovie(target, attemptedAt, null, ex);
      }
    }

    @Override
    public void persist(FetchedMovie fetched) {
      var target = fetched.target();
      var outcome =
          fetched.thrown() == null
              ? applyMovieMetadata(target.movie(), target.tmdbId(), fetched.details(), artworkRun)
              : refreshFailed(target.movie(), target.tmdbId(), fetched.thrown());

      itemResults.trySave(
          metadataResult(target.movie().getId(), ImageEntityType.MOVIE)
              .outcome(outcome)
              .attemptedAt(fetched.attemptedAt())
              .build());
    }
  }

  private void refreshMovie(Movie movie, String tmdbId, Library library, ArtworkRun artworkRun) {
    var attemptedAt = clock.instant();
    var outcome = refreshMovieMetadata(movie, tmdbId, library, artworkRun);

    itemResults.trySave(
        metadataResult(movie.getId(), ImageEntityType.MOVIE)
            .outcome(outcome)
            .attemptedAt(attemptedAt)
            .build());
  }

  private ItemOutcome refreshMovieMetadata(
      Movie movie, String tmdbId, Library library, ArtworkRun artworkRun) {
    try {
      return applyMovieMetadata(
          movie,
          tmdbId,
          movieMetadataProviderResolver.getMetadata(
              searchResult(tmdbId, movie.getTitle()), library),
          artworkRun);
    } catch (RuntimeException ex) {
      return refreshFailed(movie, tmdbId, ex);
    }
  }

  private ItemOutcome applyMovieMetadata(
      Movie movie,
      String tmdbId,
      MetadataFetchOutcome<MetadataResult<Movie>> details,
      ArtworkRun artworkRun) {
    try {
      return switch (details) {
        case MetadataFetchOutcome.Found(var metadataResult) -> {
          movieService.refreshMovieMetadata(movie, metadataResult, artworkRun);
          yield new ItemOutcome.Succeeded();
        }
        case MetadataFetchOutcome.NotFound<?> _ -> {
          log.error("TMDB no longer has movie '{}' TMDB id '{}'", movie.getTitle(), tmdbId);
          yield new ItemOutcome.Unavailable();
        }
        case MetadataFetchOutcome.Failed<?> failed -> {
          log.error(
              "Failed to fetch metadata for movie '{}' TMDB id '{}'", movie.getTitle(), tmdbId);
          yield failed.toItemOutcome();
        }
      };
    } catch (RuntimeException ex) {
      return refreshFailed(movie, tmdbId, ex);
    }
  }

  private static ItemOutcome refreshFailed(Movie movie, String tmdbId, RuntimeException ex) {
    log.error("Failed to refresh movie '{}' TMDB id '{}'", movie.getTitle(), tmdbId, ex);
    return ItemOutcome.Failed.of(ItemFailureReason.TEMPORARY, ex);
  }

  private static RemoteSearchResult searchResult(String tmdbId, String title) {
    return RemoteSearchResult.builder()
        .externalId(tmdbId)
        .externalSourceType(ExternalSourceType.TMDB)
        .title(title)
        .build();
  }

  private static ItemResult.ItemResultBuilder metadataResult(
      UUID itemId, ImageEntityType itemType) {
    return ItemResult.builder().itemId(itemId).itemType(itemType).step(ItemStep.METADATA);
  }

  private static void throwIfAnyRefreshTaskFailed(Library library, List<Future<?>> tasks) {
    throwIfAnyRefreshFailed(
        library,
        tasks.stream()
            .filter(task -> task.state() == Future.State.FAILED)
            .map(Future::exceptionNow)
            .toList());
  }

  private static void throwIfAnyRefreshFailed(Library library, List<Throwable> failures) {
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
