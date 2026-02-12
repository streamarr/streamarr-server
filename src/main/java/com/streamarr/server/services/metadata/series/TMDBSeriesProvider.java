package com.streamarr.server.services.metadata.series;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ContentRating;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.TheMovieDatabaseHttpService;
import com.streamarr.server.services.metadata.TmdbMetadataMapper;
import com.streamarr.server.services.metadata.TmdbSearchResultScorer;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.tmdb.TmdbContentRatings;
import com.streamarr.server.services.metadata.tmdb.TmdbCredits;
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
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;

@Slf4j
@Service
@RequiredArgsConstructor
public class TMDBSeriesProvider implements SeriesMetadataProvider {

  private final TheMovieDatabaseHttpService theMovieDatabaseHttpService;

  private final ConcurrentHashMap<String, List<TmdbTvSeasonSummary>> seasonSummariesCache =
      new ConcurrentHashMap<>();

  @Getter private final ExternalAgentStrategy agentStrategy = ExternalAgentStrategy.TMDB;

  public Optional<RemoteSearchResult> search(VideoFileParserResult videoInformation) {
    var findResult = searchByExternalId(videoInformation);
    if (findResult.isPresent()) {
      return findResult;
    }

    return searchByText(videoInformation);
  }

  private Optional<RemoteSearchResult> searchByExternalId(VideoFileParserResult videoInformation) {
    if (StringUtils.isBlank(videoInformation.externalId())
        || videoInformation.externalSource() == null) {
      return Optional.empty();
    }

    if (videoInformation.externalSource() == ExternalSourceType.TMDB) {
      return searchByDirectTmdbId(videoInformation);
    }

    var tmdbSource = TheMovieDatabaseHttpService.EXTERNAL_SOURCES.get(videoInformation.externalSource());
    if (tmdbSource == null) {
      return Optional.empty();
    }

    try {
      var findResults =
          theMovieDatabaseHttpService.findByExternalId(videoInformation.externalId(), tmdbSource);

      var tvResults = findResults.getTvResults();
      if (tvResults != null && !tvResults.isEmpty()) {
        var tmdbResult = tvResults.getFirst();
        return Optional.of(
            RemoteSearchResult.builder()
                .externalSourceType(ExternalSourceType.TMDB)
                .externalId(String.valueOf(tmdbResult.getId()))
                .title(tmdbResult.getName())
                .build());
      }
    } catch (IOException | JacksonException ex) {
      log.warn(
          "TMDB /find failed for external ID '{}', falling back to text search",
          videoInformation.externalId(),
          ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("TMDB /find interrupted for external ID '{}'", videoInformation.externalId(), ex);
      return Optional.empty();
    }

    return Optional.empty();
  }

  private Optional<RemoteSearchResult> searchByDirectTmdbId(
      VideoFileParserResult videoInformation) {
    try {
      var tmdbSeries =
          theMovieDatabaseHttpService.getTvSeriesMetadata(videoInformation.externalId());

      return Optional.of(
          RemoteSearchResult.builder()
              .externalSourceType(ExternalSourceType.TMDB)
              .externalId(String.valueOf(tmdbSeries.getId()))
              .title(tmdbSeries.getName())
              .build());
    } catch (IOException | JacksonException ex) {
      log.warn(
          "TMDB direct lookup failed for ID '{}', falling back to text search",
          videoInformation.externalId(),
          ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("TMDB direct lookup interrupted for ID '{}'", videoInformation.externalId(), ex);
      return Optional.empty();
    }

    return Optional.empty();
  }

  private Optional<RemoteSearchResult> searchByText(VideoFileParserResult videoInformation) {
    try {
      var searchResult = theMovieDatabaseHttpService.searchForTvSeries(videoInformation);

      if (searchResult.getResults().isEmpty()) {
        return Optional.empty();
      }

      var results = searchResult.getResults();
      var candidates =
          results.stream()
              .map(
                  r ->
                      new TmdbSearchResultScorer.CandidateResult(
                          r.getName(), r.getOriginalName(), r.getFirstAirDate(), r.getPopularity()))
              .toList();

      var bestIndex =
          TmdbSearchResultScorer.selectBestMatch(
              videoInformation.title(), videoInformation.year(), candidates);

      if (bestIndex.isEmpty()) {
        return Optional.empty();
      }

      var tmdbResult = results.get(bestIndex.getAsInt());

      return Optional.of(
          RemoteSearchResult.builder()
              .externalSourceType(ExternalSourceType.TMDB)
              .externalId(String.valueOf(tmdbResult.getId()))
              .title(tmdbResult.getName())
              .build());

    } catch (IOException ex) {
      log.error("Failure requesting TV search results:", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error("TV search interrupted:", ex);
    }

    return Optional.empty();
  }

  public Optional<MetadataResult<Series>> getMetadata(
      RemoteSearchResult remoteSearchResult, Library library) {
    try {
      var tmdbSeries =
          theMovieDatabaseHttpService.getTvSeriesMetadata(remoteSearchResult.externalId());

      seasonSummariesCache.put(
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

      return Optional.of(
          new MetadataResult<>(
              seriesBuilder.build(), imageSources, personImageSources, companyImageSources));

    } catch (IOException ex) {
      log.error(
          "Failure enriching series metadata using TMDB id '{}'",
          remoteSearchResult.externalId(),
          ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error(
          "Series metadata enrichment interrupted for TMDB id '{}'",
          remoteSearchResult.externalId(),
          ex);
    }

    return Optional.empty();
  }

  public Optional<SeasonDetails> getSeasonDetails(String seriesExternalId, int seasonNumber) {
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

      return Optional.of(seasonBuilder.build());

    } catch (IOException ex) {
      log.error(
          "Failure fetching season {} details for series TMDB id '{}'",
          seasonNumber,
          seriesExternalId,
          ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.error(
          "Season details fetch interrupted for series TMDB id '{}' season {}",
          seriesExternalId,
          seasonNumber,
          ex);
    }

    return Optional.empty();
  }

  @Override
  public OptionalInt resolveSeasonNumber(String seriesExternalId, int parsedSeasonNumber) {
    var summaries = getOrFetchSeasonSummaries(seriesExternalId);

    if (summaries.stream().anyMatch(s -> s.getSeasonNumber() == parsedSeasonNumber)) {
      return OptionalInt.of(parsedSeasonNumber);
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
        .mapToInt(TmdbTvSeasonSummary::getSeasonNumber)
        .findFirst();
  }

  private List<TmdbTvSeasonSummary> getOrFetchSeasonSummaries(String seriesExternalId) {
    return seasonSummariesCache.computeIfAbsent(
        seriesExternalId,
        id -> {
          try {
            var series = theMovieDatabaseHttpService.getTvSeriesMetadata(id);
            return Optional.ofNullable(series.getSeasons()).orElse(Collections.emptyList());
          } catch (IOException ex) {
            log.warn("Failed to fetch season summaries for TMDB id '{}'", id, ex);
            return Collections.emptyList();
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Season summaries fetch interrupted for TMDB id '{}'", id, ex);
            return Collections.emptyList();
          }
        });
  }

  private SeasonDetails.EpisodeDetails mapEpisodeDetails(
      com.streamarr.server.services.metadata.tmdb.TmdbTvEpisode ep) {
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
