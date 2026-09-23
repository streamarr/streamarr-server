package com.streamarr.server.services.metadata.series;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ContentRating;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.events.library.RefreshEndedEvent;
import com.streamarr.server.services.events.library.ScanEndedEvent;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.MetadataSearchOutcome;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.TheMovieDatabaseHttpService;
import com.streamarr.server.services.metadata.TmdbMetadataMapper;
import com.streamarr.server.services.metadata.TmdbSearchDelegate;
import com.streamarr.server.services.metadata.TmdbSearchResultScorer;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.tmdb.TmdbContentRatings;
import com.streamarr.server.services.metadata.tmdb.TmdbCredits;
import com.streamarr.server.services.metadata.tmdb.TmdbFindResults;
import com.streamarr.server.services.metadata.tmdb.TmdbTvEpisode;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSeasonSummary;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSeries;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import com.streamarr.server.utils.TitleSortUtil;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TMDBSeriesProvider implements SeriesMetadataProvider {

  private final TheMovieDatabaseHttpService theMovieDatabaseHttpService;
  private final TmdbSearchDelegate searchDelegate;

  private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, List<TmdbTvSeasonSummary>>>
      seasonSummariesByLibrary = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, IOException>>
      failedSeasonDetailsByLibrary = new ConcurrentHashMap<>();

  @Getter private final ExternalAgentStrategy agentStrategy = ExternalAgentStrategy.TMDB;

  public MetadataSearchOutcome search(VideoFileParserResult videoInformation) {
    return searchDelegate.search(
        videoInformation,
        this::extractFindResult,
        this::lookupAndCacheByDirectTmdbId,
        this::searchByText);
  }

  private Optional<RemoteSearchResult> extractFindResult(TmdbFindResults findResults) {
    var tvResults = findResults.getTvResults();
    if (tvResults == null || tvResults.isEmpty()) {
      return Optional.empty();
    }
    var tmdbResult = tvResults.getFirst();
    return Optional.of(
        RemoteSearchResult.builder()
            .externalSourceType(ExternalSourceType.TMDB)
            .externalId(String.valueOf(tmdbResult.getId()))
            .title(tmdbResult.getName())
            .build());
  }

  private RemoteSearchResult lookupAndCacheByDirectTmdbId(String externalId)
      throws IOException, InterruptedException {
    var tmdbSeries = theMovieDatabaseHttpService.getTvSeriesMetadata(externalId);
    return RemoteSearchResult.builder()
        .externalSourceType(ExternalSourceType.TMDB)
        .externalId(String.valueOf(tmdbSeries.getId()))
        .title(tmdbSeries.getName())
        .build();
  }

  private MetadataSearchOutcome searchByText(VideoFileParserResult videoInformation) {
    return searchDelegate.searchByText(
        videoInformation,
        info -> theMovieDatabaseHttpService.searchForTvSeries(info).getResults(),
        r ->
            new TmdbSearchResultScorer.CandidateResult(
                r.getName(), r.getOriginalName(), r.getFirstAirDate(), r.getPopularity()),
        r ->
            RemoteSearchResult.builder()
                .externalSourceType(ExternalSourceType.TMDB)
                .externalId(String.valueOf(r.getId()))
                .title(r.getName())
                .build());
  }

  public MetadataFetchOutcome<MetadataResult<Series>> getMetadata(
      RemoteSearchResult remoteSearchResult, Library library) {
    try {
      var tmdbSeries =
          theMovieDatabaseHttpService.getTvSeriesMetadata(remoteSearchResult.externalId());

      seasonSummariesByLibrary
          .computeIfAbsent(library.getId(), _ -> new ConcurrentHashMap<>())
          .put(
              remoteSearchResult.externalId(),
              Optional.ofNullable(tmdbSeries.getSeasons()).orElse(Collections.emptyList()));

      var credits = Optional.ofNullable(tmdbSeries.getCredits());
      var castList = credits.map(TmdbCredits::getCast).orElse(Collections.emptyList());
      var crewList = credits.map(TmdbCredits::getCrew).orElse(Collections.emptyList());
      var contentRatingsResult = Optional.ofNullable(tmdbSeries.getContentRatings());
      var productionCompanies =
          Optional.ofNullable(tmdbSeries.getProductionCompanies()).orElse(Collections.emptyList());
      var tmdbGenres = Optional.ofNullable(tmdbSeries.getGenres()).orElse(Collections.emptyList());

      var tvRating =
          contentRatingsResult
              .map(TmdbContentRatings::getResults)
              .orElse(Collections.emptyList())
              .stream()
              .filter(r -> "US".equals(r.getIso31661()))
              .filter(r -> StringUtils.isNotBlank(r.getRating()))
              .findFirst();

      var runtime = computeRuntime(tmdbSeries);

      var seriesBuilder =
          Series.builder()
              .library(library)
              .title(tmdbSeries.getName())
              .originalTitle(tmdbSeries.getOriginalName())
              .titleSort(TitleSortUtil.computeTitleSort(tmdbSeries.getName()))
              .externalIds(mapExternalIds(tmdbSeries))
              .tagline(tmdbSeries.getTagline())
              .summary(tmdbSeries.getOverview())
              .runtime(runtime)
              .studios(TmdbMetadataMapper.mapCompanies(productionCompanies))
              .cast(TmdbMetadataMapper.mapCast(castList))
              .directors(TmdbMetadataMapper.mapDirectors(crewList))
              .genres(TmdbMetadataMapper.mapGenres(tmdbGenres));

      if (StringUtils.isNotBlank(tmdbSeries.getFirstAirDate())) {
        seriesBuilder.firstAirDate(LocalDate.parse(tmdbSeries.getFirstAirDate()));
      }

      tvRating.ifPresent(
          rating ->
              seriesBuilder.contentRating(
                  new ContentRating("TV Parental Guidelines", rating.getRating(), "US")));

      var imageSources =
          TmdbMetadataMapper.buildPosterAndBackdropSources(
              tmdbSeries.getPosterPath(), tmdbSeries.getBackdropPath());
      var personImageSources = TmdbMetadataMapper.buildPersonImageSources(castList, crewList);
      var companyImageSources = TmdbMetadataMapper.buildCompanyImageSources(productionCompanies);

      return new MetadataFetchOutcome.Found<>(
          new MetadataResult<>(
              seriesBuilder.build(), imageSources, personImageSources, companyImageSources));

    } catch (IOException ex) {
      log.error(
          "Failure enriching series metadata using TMDB id '{}'",
          remoteSearchResult.externalId(),
          ex);
      return TmdbMetadataMapper.fetchFailure(ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error(
          "Series metadata enrichment interrupted for TMDB id '{}'",
          remoteSearchResult.externalId(),
          ex);
      return new MetadataFetchOutcome.Failed<>(ex);
    }
  }

  public MetadataFetchOutcome<SeasonDetails> getSeasonDetails(
      UUID libraryId, String seriesExternalId, int seasonNumber) {
    var cacheKey = seriesExternalId + ":" + seasonNumber;
    var failedCache =
        failedSeasonDetailsByLibrary.computeIfAbsent(libraryId, _ -> new ConcurrentHashMap<>());

    var earlierFailure = failedCache.get(cacheKey);
    if (earlierFailure != null) {
      return TmdbMetadataMapper.fetchFailure(earlierFailure);
    }

    if (getOrFetchSeasonSummaries(libraryId, seriesExternalId)
            instanceof MetadataFetchOutcome.Found(var summaries)
        && !summaries.isEmpty()
        && summaries.stream().noneMatch(s -> s.getSeasonNumber() == seasonNumber)) {
      log.debug(
          "Season {} not found in summaries for series TMDB id '{}', skipping API call",
          seasonNumber,
          seriesExternalId);
      return new MetadataFetchOutcome.NotFound<>();
    }

    try {
      var tmdbSeason =
          theMovieDatabaseHttpService.getTvSeasonDetails(seriesExternalId, seasonNumber);

      var episodes =
          Optional.ofNullable(tmdbSeason.getEpisodes()).orElse(Collections.emptyList()).stream()
              .map(this::mapEpisodeDetails)
              .toList();

      var seasonImageSources = buildSeasonImageSources(tmdbSeason.getPosterPath());

      var seasonBuilder =
          SeasonDetails.builder()
              .name(tmdbSeason.getName())
              .seasonNumber(tmdbSeason.getSeasonNumber())
              .overview(tmdbSeason.getOverview())
              .imageSources(seasonImageSources)
              .episodes(episodes);

      if (StringUtils.isNotBlank(tmdbSeason.getAirDate())) {
        seasonBuilder.airDate(LocalDate.parse(tmdbSeason.getAirDate()));
      }

      return new MetadataFetchOutcome.Found<>(seasonBuilder.build());

    } catch (IOException ex) {
      failedCache.put(cacheKey, ex);
      log.error(
          "Failure fetching season {} details for series TMDB id '{}'",
          seasonNumber,
          seriesExternalId,
          ex);
      return TmdbMetadataMapper.fetchFailure(ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error(
          "Season details fetch interrupted for series TMDB id '{}' season {}",
          seriesExternalId,
          seasonNumber,
          ex);
      return new MetadataFetchOutcome.Failed<>(ex);
    }
  }

  @Override
  public MetadataFetchOutcome<Integer> resolveSeasonNumber(
      UUID libraryId, String seriesExternalId, int parsedSeasonNumber) {
    return getOrFetchSeasonSummaries(libraryId, seriesExternalId)
        .flatMap(summaries -> seasonNumberIn(summaries, parsedSeasonNumber));
  }

  private static MetadataFetchOutcome<Integer> seasonNumberIn(
      List<TmdbTvSeasonSummary> summaries, int parsedSeasonNumber) {
    if (summaries.stream().anyMatch(s -> s.getSeasonNumber() == parsedSeasonNumber)) {
      return new MetadataFetchOutcome.Found<>(parsedSeasonNumber);
    }

    return summaries.stream()
        .filter(s -> StringUtils.isNotBlank(s.getAirDate()))
        .filter(
            s -> {
              try {
                return LocalDate.parse(s.getAirDate()).getYear() == parsedSeasonNumber;
              } catch (DateTimeParseException _) {
                return false;
              }
            })
        .map(TmdbTvSeasonSummary::getSeasonNumber)
        .<MetadataFetchOutcome<Integer>>map(MetadataFetchOutcome.Found::new)
        .findFirst()
        .orElseGet(MetadataFetchOutcome.NotFound::new);
  }

  @Override
  public MetadataFetchOutcome<List<Integer>> getAvailableSeasonNumbers(
      UUID libraryId, String seriesExternalId) {
    return getOrFetchSeasonSummaries(libraryId, seriesExternalId)
        .map(summaries -> summaries.stream().map(TmdbTvSeasonSummary::getSeasonNumber).toList());
  }

  @EventListener
  public void onScanEnded(ScanEndedEvent event) {
    log.debug("Clearing series metadata cache for library {}", event.libraryId());
    seasonSummariesByLibrary.remove(event.libraryId());
    failedSeasonDetailsByLibrary.remove(event.libraryId());
  }

  @EventListener
  public void onRefreshEnded(RefreshEndedEvent event) {
    log.debug("Clearing series metadata cache for library {} after refresh", event.libraryId());
    seasonSummariesByLibrary.remove(event.libraryId());
    failedSeasonDetailsByLibrary.remove(event.libraryId());
  }

  // Only fetched summaries are cached, so a failed fetch is retried on the next lookup.
  private MetadataFetchOutcome<List<TmdbTvSeasonSummary>> getOrFetchSeasonSummaries(
      UUID libraryId, String seriesExternalId) {
    var libraryCache =
        seasonSummariesByLibrary.computeIfAbsent(libraryId, _ -> new ConcurrentHashMap<>());
    var cached = libraryCache.get(seriesExternalId);
    if (cached != null) {
      return new MetadataFetchOutcome.Found<>(cached);
    }

    return fetchSeasonSummaries(seriesExternalId)
        .map(
            fetched -> {
              var existing = libraryCache.putIfAbsent(seriesExternalId, fetched);
              if (existing != null) {
                return existing;
              }

              return fetched;
            });
  }

  private MetadataFetchOutcome<List<TmdbTvSeasonSummary>> fetchSeasonSummaries(
      String seriesExternalId) {
    try {
      var series = theMovieDatabaseHttpService.getTvSeriesMetadata(seriesExternalId);
      return new MetadataFetchOutcome.Found<>(
          Optional.ofNullable(series.getSeasons()).orElse(Collections.emptyList()));
    } catch (IOException ex) {
      log.warn("Failed to fetch season summaries for TMDB id '{}'", seriesExternalId, ex);
      return TmdbMetadataMapper.fetchFailure(ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.warn("Season summaries fetch interrupted for TMDB id '{}'", seriesExternalId, ex);
      return new MetadataFetchOutcome.Failed<>(ex);
    }
  }

  private SeasonDetails.EpisodeDetails mapEpisodeDetails(TmdbTvEpisode ep) {
    var builder =
        SeasonDetails.EpisodeDetails.builder()
            .episodeNumber(ep.getEpisodeNumber())
            .name(ep.getName())
            .overview(ep.getOverview())
            .imageSources(buildEpisodeImageSources(ep.getStillPath()))
            .runtime(ep.getRuntime());

    if (StringUtils.isNotBlank(ep.getAirDate())) {
      builder.airDate(LocalDate.parse(ep.getAirDate()));
    }

    return builder.build();
  }

  private Integer computeRuntime(TmdbTvSeries tmdbSeries) {
    var episodeRunTime = tmdbSeries.getEpisodeRunTime();

    if (episodeRunTime == null || episodeRunTime.isEmpty()) {
      return null;
    }

    return (int) episodeRunTime.stream().mapToInt(Integer::intValue).average().orElse(0);
  }

  private Set<ExternalIdentifier> mapExternalIds(TmdbTvSeries tmdbSeries) {
    var externalIdSet = new HashSet<ExternalIdentifier>();

    externalIdSet.add(
        ExternalIdentifier.builder()
            .externalSourceType(ExternalSourceType.TMDB)
            .externalId(String.valueOf(tmdbSeries.getId()))
            .build());

    var externalIds = tmdbSeries.getExternalIds();
    if (externalIds != null && StringUtils.isNotBlank(externalIds.getImdbId())) {
      externalIdSet.add(
          ExternalIdentifier.builder()
              .externalSourceType(ExternalSourceType.IMDB)
              .externalId(externalIds.getImdbId())
              .build());
    }

    return externalIdSet;
  }

  private List<ImageSource> buildSeasonImageSources(String posterPath) {
    if (StringUtils.isNotBlank(posterPath)) {
      return List.of(new TmdbImageSource(ImageType.POSTER, posterPath));
    }
    return List.of();
  }

  private List<ImageSource> buildEpisodeImageSources(String stillPath) {
    if (StringUtils.isNotBlank(stillPath)) {
      return List.of(new TmdbImageSource(ImageType.STILL, stillPath));
    }
    return List.of();
  }
}
