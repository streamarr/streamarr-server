package com.streamarr.server.services;

import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.pagination.LetterJumpResolver;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationService;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SeriesService {

  private final SeriesRepository seriesRepository;
  private final CreditService creditService;
  private final PaginationService paginationService;
  private final ArtworkRequestService artworkRequestService;
  private final ImageService imageService;
  private final SeasonRepository seasonRepository;
  private final EpisodeRepository episodeRepository;
  private final MediaFileRepository mediaFileRepository;
  private final PersonRepository personRepository;
  private final GenreRepository genreRepository;
  private final CompanyRepository companyRepository;
  private final WatchHistoryRepository watchHistoryRepository;

  @Transactional
  public Series saveSeriesWithMediaFile(Series series, MediaFile mediaFile) {
    var savedSeries = seriesRepository.saveAndFlush(series);

    savedSeries.addFile(mediaFile);

    return seriesRepository.save(savedSeries);
  }

  @Transactional(readOnly = true)
  public Optional<Series> findByTmdbId(String tmdbId) {
    return seriesRepository.findByTmdbId(tmdbId);
  }

  @Transactional
  public Series saveSeries(Series series) {
    return seriesRepository.saveAndFlush(series);
  }

  @Transactional
  public Series createSeriesWithAssociations(
      MetadataResult<Series> metadataResult, ArtworkRun artworkRun) {
    var series = metadataResult.entity();
    creditService
        .getOrCreateCredits(
            MediaTitleCreditsRequest.fromMetadataResult(
                metadataResult, ImageRefreshMode.PRESERVE, artworkRun))
        .copyTo(series);

    var savedSeries = seriesRepository.saveAndFlush(series);

    artworkRequestService.requestPrimaryArtwork(
        artworkRun,
        ArtworkSources.builder()
            .targetId(savedSeries.getId())
            .targetType(ImageEntityType.SERIES)
            .sources(metadataResult.imageSources())
            .build());

    return savedSeries;
  }

  @Transactional
  public Season createSeasonWithEpisodes(SeasonWithEpisodesRequest request) {
    var series = request.series();
    var details = request.details();
    var library = request.library();
    var season =
        seasonRepository.saveAndFlush(
            Season.builder()
                .title(details.name())
                .seasonNumber(details.seasonNumber())
                .overview(details.overview())
                .airDate(details.airDate())
                .series(series)
                .library(library)
                .build());

    artworkRequestService.requestPrimaryArtwork(
        request.artworkRun(),
        ArtworkSources.builder()
            .targetId(season.getId())
            .targetType(ImageEntityType.SEASON)
            .sources(details.imageSources())
            .build());

    var episodes =
        details.episodes().stream()
            .map(
                ep ->
                    Episode.builder()
                        .title(ep.name())
                        .episodeNumber(ep.episodeNumber())
                        .overview(ep.overview())
                        .airDate(ep.airDate())
                        .runtime(ep.runtime())
                        .season(season)
                        .library(library)
                        .build())
            .toList();

    var savedEpisodes = episodeRepository.saveAllAndFlush(episodes);
    requestEpisodeArtwork(savedEpisodes, request);

    return season;
  }

  @Transactional
  public Series refreshSeriesMetadata(
      Series existing, MetadataResult<Series> metadataResult, ArtworkRun artworkRun) {
    var fresh = metadataResult.entity();

    existing.setTitle(fresh.getTitle());
    existing.setOriginalTitle(fresh.getOriginalTitle());
    existing.setTitleSort(fresh.getTitleSort());
    existing.setTagline(fresh.getTagline());
    existing.setSummary(fresh.getSummary());
    existing.setRuntime(fresh.getRuntime());
    existing.setContentRating(fresh.getContentRating());
    existing.setFirstAirDate(fresh.getFirstAirDate());
    creditService
        .getOrCreateCredits(
            MediaTitleCreditsRequest.fromMetadataResult(
                metadataResult, artworkRun.imageRefreshMode(), artworkRun))
        .copyTo(existing);

    var saved = seriesRepository.saveAndFlush(existing);
    artworkRequestService.requestPrimaryArtwork(
        artworkRun,
        ArtworkSources.builder()
            .targetId(saved.getId())
            .targetType(ImageEntityType.SERIES)
            .sources(metadataResult.imageSources())
            .build());
    return saved;
  }

  @Transactional
  public Season refreshSeasonWithEpisodes(SeasonWithEpisodesRequest request) {
    var series = request.series();
    var details = request.details();
    var library = request.library();
    var season =
        seasonRepository
            .findBySeriesIdAndSeasonNumber(series.getId(), details.seasonNumber())
            .orElseGet(() -> Season.builder().series(series).library(library).build());

    season.setTitle(details.name());
    season.setSeasonNumber(details.seasonNumber());
    season.setOverview(details.overview());
    season.setAirDate(details.airDate());

    var savedSeason = seasonRepository.saveAndFlush(season);
    artworkRequestService.requestPrimaryArtwork(
        request.artworkRun(),
        ArtworkSources.builder()
            .targetId(savedSeason.getId())
            .targetType(ImageEntityType.SEASON)
            .sources(details.imageSources())
            .build());

    var episodes =
        details.episodes().stream()
            .map(
                epDetails -> {
                  var episode =
                      episodeRepository
                          .findBySeasonIdAndEpisodeNumber(
                              savedSeason.getId(), epDetails.episodeNumber())
                          .orElseGet(
                              () -> Episode.builder().season(savedSeason).library(library).build());

                  episode.setTitle(epDetails.name());
                  episode.setEpisodeNumber(epDetails.episodeNumber());
                  episode.setOverview(epDetails.overview());
                  episode.setAirDate(epDetails.airDate());
                  episode.setRuntime(epDetails.runtime());

                  return episode;
                })
            .toList();

    var savedEpisodes = episodeRepository.saveAllAndFlush(episodes);
    requestEpisodeArtwork(savedEpisodes, request);

    return savedSeason;
  }

  private void requestEpisodeArtwork(
      List<? extends Episode> episodes, SeasonWithEpisodesRequest request) {
    for (var episode : episodes) {
      request.details().episodes().stream()
          .filter(details -> details.episodeNumber() == episode.getEpisodeNumber())
          .findFirst()
          .ifPresent(
              details ->
                  artworkRequestService.requestPrimaryArtwork(
                      request.artworkRun(),
                      ArtworkSources.builder()
                          .targetId(episode.getId())
                          .targetType(ImageEntityType.EPISODE)
                          .sources(details.imageSources())
                          .build()));
    }
  }

  @Transactional
  public Series addMediaFile(UUID seriesId, MediaFile mediaFile) {
    var series = seriesRepository.findById(seriesId).orElseThrow();
    series.addFile(mediaFile);

    return seriesRepository.saveAndFlush(series);
  }

  @Transactional
  public void deleteByLibraryId(UUID libraryId) {
    deleteSeries(seriesRepository.findByLibrary_Id(libraryId));
  }

  @Transactional
  public void deleteSeriesById(UUID seriesId) {
    seriesRepository.findById(seriesId).ifPresent(series -> deleteSeries(List.of(series)));
  }

  private void deleteSeries(List<Series> seriesList) {
    if (seriesList.isEmpty()) {
      return;
    }

    imageService.prepareSeriesDeletion(seriesList.stream().map(Series::getId).toList());
    seriesRepository.deleteAll(seriesList);
  }

  @Transactional(readOnly = true)
  public Optional<Series> findById(UUID id) {
    return seriesRepository.findById(id);
  }

  /** Returns the episodes with the given ids; an id without an episode is left out. */
  @Transactional(readOnly = true)
  public List<Episode> findEpisodesByIds(Collection<UUID> episodeIds) {
    return episodeRepository.findAllById(episodeIds);
  }

  /** Returns the seasons that hold the given episodes, each season once. */
  @Transactional(readOnly = true)
  public List<Season> findSeasonsByEpisodeIds(Collection<UUID> episodeIds) {
    return seasonRepository.findAllById(findSeasonIdsByEpisodeIds(episodeIds));
  }

  /** Returns the series that hold the given episodes, each series once. */
  @Transactional(readOnly = true)
  public List<Series> findSeriesByEpisodeIds(Collection<UUID> episodeIds) {
    var seriesIds =
        seasonRepository.findAllById(findSeasonIdsByEpisodeIds(episodeIds)).stream()
            .map(season -> season.getSeries().getId())
            .collect(Collectors.toSet());
    return seriesRepository.findAllById(seriesIds);
  }

  private Set<UUID> findSeasonIdsByEpisodeIds(Collection<UUID> episodeIds) {
    return episodeRepository.findAllById(episodeIds).stream()
        .map(episode -> episode.getSeason().getId())
        .collect(Collectors.toSet());
  }

  /** Returns the given series ids whose series has no provider id. */
  @Transactional(readOnly = true)
  public Set<UUID> findUnmatchedSeriesIds(Collection<UUID> seriesIds) {
    return seriesRepository.findUnmatchedSeriesIds(seriesIds);
  }

  @Transactional(readOnly = true)
  public List<MediaFile> findMediaFiles(UUID entityId) {
    return mediaFileRepository.findByMediaId(entityId);
  }

  @Transactional(readOnly = true)
  public List<Season> findSeasons(UUID seriesId) {
    return seasonRepository.findBySeriesIdOrderBySeasonNumber(seriesId);
  }

  @Transactional(readOnly = true)
  public Optional<Season> findSeasonById(UUID seasonId) {
    return seasonRepository.findById(seasonId);
  }

  @Transactional(readOnly = true)
  public Optional<Episode> findEpisodeById(UUID episodeId) {
    return episodeRepository.findById(episodeId);
  }

  @Transactional(readOnly = true)
  public List<Episode> findEpisodes(UUID seasonId) {
    return episodeRepository.findBySeasonIdOrderByEpisodeNumber(seasonId);
  }

  @Transactional(readOnly = true)
  public List<Company> findStudios(UUID seriesId) {
    return companyRepository.findBySeriesId(seriesId);
  }

  @Transactional(readOnly = true)
  public List<Person> findCast(UUID seriesId) {
    return personRepository.findCastBySeriesId(seriesId);
  }

  @Transactional(readOnly = true)
  public List<Person> findDirectors(UUID seriesId) {
    return personRepository.findDirectorsBySeriesId(seriesId);
  }

  @Transactional(readOnly = true)
  public List<Genre> findGenres(UUID seriesId) {
    return genreRepository.findBySeriesId(seriesId);
  }

  public MediaPage<Series> getSeriesWithFilter(MediaPaginationOptions options) {
    var resolvedOptions =
        LetterJumpResolver.resolve(options, seriesRepository::findLetterJumpPredecessor);

    var seriesList =
        resolvedOptions.getCursorId().isPresent()
            ? seriesRepository.seekWithFilter(resolvedOptions)
            : seriesRepository.findFirstWithFilter(resolvedOptions);

    var lastWatchedBySeriesId = lastWatchedFor(resolvedOptions.getMediaFilter(), seriesList);

    var pageItems =
        seriesList.stream()
            .map(
                series ->
                    new PageItem<>(
                        series,
                        getOrderByValue(
                            resolvedOptions.getMediaFilter(), series, lastWatchedBySeriesId)))
            .toList();

    return paginationService.buildMediaPage(
        pageItems, resolvedOptions.getPaginationOptions(), resolvedOptions.getCursorId());
  }

  private Map<UUID, Instant> lastWatchedFor(MediaFilter filter, List<Series> seriesList) {
    if (filter.getSortBy() != OrderMediaBy.LAST_WATCHED || seriesList.isEmpty()) {
      return Map.of();
    }
    return watchHistoryRepository.findLastWatchedByItemIds(
        filter.getProfileId(),
        CollectableScope.SERIES,
        seriesList.stream().map(Series::getId).toList());
  }

  private Object getOrderByValue(
      MediaFilter filter, Series series, Map<UUID, Instant> lastWatchedBySeriesId) {
    return switch (filter.getSortBy()) {
      case TITLE -> series.getTitleSort();
      case ADDED -> series.getCreatedOn();
      case RELEASE_DATE -> series.getFirstAirDate();
      case RUNTIME -> series.getRuntime();
      case LAST_WATCHED -> lastWatchedBySeriesId.get(series.getId());
    };
  }
}
