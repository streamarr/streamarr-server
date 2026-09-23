package com.streamarr.server.services;

import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.pagination.LetterJumpResolver;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SeriesService {

  private final SeriesRepository seriesRepository;
  private final PersonService personService;
  private final GenreService genreService;
  private final CompanyService companyService;
  private final PaginationService paginationService;
  private final ArtworkService artworkService;
  private final ImageService imageService;
  private final SeasonRepository seasonRepository;
  private final EpisodeRepository episodeRepository;
  private final MediaFileRepository mediaFileRepository;
  private final PersonRepository personRepository;
  private final GenreRepository genreRepository;
  private final CompanyRepository companyRepository;

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

    series.setCast(
        personService.getOrCreatePersons(series.getCast(), metadataResult.personImageSources()));
    series.setDirectors(
        personService.getOrCreatePersons(
            series.getDirectors(), metadataResult.personImageSources()));
    series.setGenres(genreService.getOrCreateGenres(series.getGenres()));
    series.setStudios(
        companyService.getOrCreateCompanies(
            series.getStudios(), metadataResult.companyImageSources()));

    var savedSeries = seriesRepository.saveAndFlush(series);

    artworkService.fetchRequired(
        artworkRun,
        ArtworkSources.builder()
            .entityId(savedSeries.getId())
            .entityType(ImageEntityType.SERIES)
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

    artworkService.fetchRequired(
        request.artworkRun(),
        ArtworkSources.builder()
            .entityId(season.getId())
            .entityType(ImageEntityType.SEASON)
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

    var savedEpisodes = episodeRepository.saveAll(episodes);
    fetchEpisodeArtwork(savedEpisodes, request);

    return season;
  }

  @Transactional
  public Series refreshSeriesMetadata(
      Series existing, MetadataResult<Series> metadataResult, ArtworkRun artworkRun) {
    var fresh = metadataResult.entity();
    var imageRefreshMode = artworkRun.imageRefreshMode();

    existing.setTitle(fresh.getTitle());
    existing.setOriginalTitle(fresh.getOriginalTitle());
    existing.setTitleSort(fresh.getTitleSort());
    existing.setTagline(fresh.getTagline());
    existing.setSummary(fresh.getSummary());
    existing.setRuntime(fresh.getRuntime());
    existing.setContentRating(fresh.getContentRating());
    existing.setFirstAirDate(fresh.getFirstAirDate());

    existing.setCast(
        personService.getOrCreatePersons(
            fresh.getCast(), metadataResult.personImageSources(), imageRefreshMode));
    existing.setDirectors(
        personService.getOrCreatePersons(
            fresh.getDirectors(), metadataResult.personImageSources(), imageRefreshMode));
    existing.setGenres(genreService.getOrCreateGenres(fresh.getGenres()));
    existing.setStudios(
        companyService.getOrCreateCompanies(
            fresh.getStudios(), metadataResult.companyImageSources(), imageRefreshMode));

    var saved = seriesRepository.saveAndFlush(existing);
    artworkService.fetchRequired(
        artworkRun,
        ArtworkSources.builder()
            .entityId(saved.getId())
            .entityType(ImageEntityType.SERIES)
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
    artworkService.fetchRequired(
        request.artworkRun(),
        ArtworkSources.builder()
            .entityId(savedSeason.getId())
            .entityType(ImageEntityType.SEASON)
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
    fetchEpisodeArtwork(savedEpisodes, request);

    return savedSeason;
  }

  private void fetchEpisodeArtwork(
      List<? extends Episode> episodes, SeasonWithEpisodesRequest request) {
    for (var episode : episodes) {
      request.details().episodes().stream()
          .filter(details -> details.episodeNumber() == episode.getEpisodeNumber())
          .findFirst()
          .ifPresent(
              details ->
                  artworkService.fetchRequired(
                      request.artworkRun(),
                      ArtworkSources.builder()
                          .entityId(episode.getId())
                          .entityType(ImageEntityType.EPISODE)
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
    var seriesList = seriesRepository.findByLibrary_Id(libraryId);

    if (seriesList.isEmpty()) {
      return;
    }

    for (var series : seriesList) {
      imageService.deleteImagesForEntity(series.getId(), ImageEntityType.SERIES);
    }

    seriesRepository.deleteAll(seriesList);
  }

  @Transactional
  public void deleteSeriesById(UUID seriesId) {
    imageService.deleteImagesForEntity(seriesId, ImageEntityType.SERIES);
    seriesRepository.deleteById(seriesId);
  }

  @Transactional(readOnly = true)
  public Optional<Series> findById(UUID id) {
    return seriesRepository.findById(id);
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
    return seriesRepository.findLastWatchedBySeriesIds(
        filter.getProfileId(), seriesList.stream().map(Series::getId).toList());
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
