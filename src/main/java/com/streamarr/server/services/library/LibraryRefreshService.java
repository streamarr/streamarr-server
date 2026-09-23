package com.streamarr.server.services.library;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.exceptions.UnsupportedMediaTypeException;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.ArtworkRun;
import com.streamarr.server.services.ArtworkService;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeasonWithEpisodesRequest;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.movie.MovieMetadataProviderResolver;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import java.util.Optional;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var series : seriesList) {
        var tmdbId = findTmdbId(series);
        if (tmdbId.isEmpty()) {
          log.warn("No TMDB ID for series '{}', skipping refresh", series.getTitle());
          continue;
        }
        var id = tmdbId.get();
        executor.submit(() -> refreshSeries(series, id, library, artworkRun));
      }
    }
  }

  private void refreshSeries(Series series, String tmdbId, Library library, ArtworkRun artworkRun) {
    try {
      var searchResult =
          RemoteSearchResult.builder()
              .externalId(tmdbId)
              .externalSourceType(ExternalSourceType.TMDB)
              .title(series.getTitle())
              .build();

      var metadataOpt = seriesMetadataProviderResolver.getMetadata(searchResult, library);
      if (metadataOpt.isEmpty()) {
        log.error(
            "Failed to fetch metadata for series '{}' TMDB id '{}'", series.getTitle(), tmdbId);
        return;
      }

      var refreshedSeries =
          seriesService.refreshSeriesMetadata(series, metadataOpt.get(), artworkRun);

      var seasonNumbers = seriesMetadataProviderResolver.getAvailableSeasonNumbers(library, tmdbId);

      for (var seasonNumber : seasonNumbers) {
        var seasonDetailsOpt =
            seriesMetadataProviderResolver.getSeasonDetails(library, tmdbId, seasonNumber);

        if (seasonDetailsOpt.isEmpty()) {
          log.warn("Failed to fetch season {} for series TMDB id '{}'", seasonNumber, tmdbId);
          continue;
        }

        seriesService.refreshSeasonWithEpisodes(
            SeasonWithEpisodesRequest.builder()
                .series(refreshedSeries)
                .details(seasonDetailsOpt.get())
                .library(library)
                .artworkRun(artworkRun)
                .build());
      }
    } catch (Exception ex) {
      log.error("Failed to refresh series '{}' TMDB id '{}'", series.getTitle(), tmdbId, ex);
    }
  }

  private void refreshMovieLibrary(Library library, ArtworkRun artworkRun) {
    var movies = movieRepository.findWithExternalIdsByLibrary_Id(library.getId());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var movie : movies) {
        var tmdbId = findTmdbId(movie);
        if (tmdbId.isEmpty()) {
          log.warn("No TMDB ID for movie '{}', skipping refresh", movie.getTitle());
          continue;
        }
        var id = tmdbId.get();
        executor.submit(() -> refreshMovie(movie, id, library, artworkRun));
      }
    }
  }

  private void refreshMovie(Movie movie, String tmdbId, Library library, ArtworkRun artworkRun) {
    try {
      var searchResult =
          RemoteSearchResult.builder()
              .externalId(tmdbId)
              .externalSourceType(ExternalSourceType.TMDB)
              .title(movie.getTitle())
              .build();

      var metadataOpt = movieMetadataProviderResolver.getMetadata(searchResult, library);
      if (metadataOpt.isEmpty()) {
        log.error("Failed to fetch metadata for movie '{}' TMDB id '{}'", movie.getTitle(), tmdbId);
        return;
      }

      movieService.refreshMovieMetadata(movie, metadataOpt.get(), artworkRun);
    } catch (Exception ex) {
      log.error("Failed to refresh movie '{}' TMDB id '{}'", movie.getTitle(), tmdbId, ex);
    }
  }

  private Optional<String> findTmdbId(BaseCollectable<?> entity) {
    return entity.getExternalIds().stream()
        .filter(eid -> eid.getExternalSourceType() == ExternalSourceType.TMDB)
        .map(ExternalIdentifier::getExternalId)
        .findFirst();
  }
}
