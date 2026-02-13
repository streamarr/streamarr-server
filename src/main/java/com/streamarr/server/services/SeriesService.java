package com.streamarr.server.services;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;
import com.streamarr.server.graphql.cursor.PaginationOptions;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import com.streamarr.server.services.metadata.series.SeasonDetails;
import graphql.relay.Connection;
import graphql.relay.DefaultEdge;
import graphql.relay.Edge;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SeriesService {

  private final SeriesRepository seriesRepository;
  private final PersonService personService;
  private final GenreService genreService;
  private final CompanyService companyService;
  private final CursorUtil cursorUtil;
  private final RelayPaginationService relayPaginationService;
  private final ApplicationEventPublisher eventPublisher;
  private final ImageService imageService;
  private final SeasonRepository seasonRepository;
  private final EpisodeRepository episodeRepository;

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
  public Series createSeriesWithAssociations(MetadataResult<Series> metadataResult) {
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

    publishImageEvent(savedSeries.getId(), ImageEntityType.SERIES, metadataResult.imageSources());

    return savedSeries;
  }

  @Transactional
  public Season createSeasonWithEpisodes(Series series, SeasonDetails details, Library library) {
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

    publishImageEvent(season.getId(), ImageEntityType.SEASON, details.imageSources());

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

    for (var episode : savedEpisodes) {
      details.episodes().stream()
          .filter(ed -> ed.episodeNumber() == episode.getEpisodeNumber())
          .findFirst()
          .ifPresent(
              ed ->
                  publishImageEvent(
                      episode.getId(), ImageEntityType.EPISODE, ed.imageSources()));
    }

    return season;
  }

  private void publishImageEvent(
      UUID entityId, ImageEntityType entityType, List<ImageSource> sources) {
    if (!sources.isEmpty()) {
      eventPublisher.publishEvent(new MetadataEnrichedEvent(entityId, entityType, sources));
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

  public Connection<? extends BaseCollectable<?>> getSeriesWithFilter(
      int first, String after, int last, String before, MediaFilter filter) {

    if (filter == null) {
      filter = buildDefaultSeriesFilter();
    }

    var paginationOptions = relayPaginationService.getPaginationOptions(first, after, last, before);

    if (paginationOptions.getCursor().isEmpty()) {
      return getFirstSeriesAsConnection(paginationOptions, filter);
    }

    var mediaOptionsFromCursor = cursorUtil.decodeMediaCursor(paginationOptions);

    validateDecodedCursorAgainstFilter(mediaOptionsFromCursor, filter);

    return usingCursorGetSeriesAsConnection(mediaOptionsFromCursor);
  }

  private MediaFilter buildDefaultSeriesFilter() {
    return MediaFilter.builder().build();
  }

  private Connection<? extends BaseCollectable<?>> getFirstSeriesAsConnection(
      PaginationOptions options, MediaFilter filter) {
    var mediaOptions =
        MediaPaginationOptions.builder().paginationOptions(options).mediaFilter(filter).build();

    var seriesList = seriesRepository.findFirstWithFilter(mediaOptions);
    var edges = mapItemsToEdges(seriesList, mediaOptions);

    return relayPaginationService.buildConnection(
        edges, mediaOptions.getPaginationOptions(), mediaOptions.getCursorId());
  }

  private List<Edge<Series>> mapItemsToEdges(
      List<Series> seriesList, MediaPaginationOptions options) {
    return seriesList.stream()
        .<Edge<Series>>map(
            result -> {
              var orderByValue = getOrderByValue(options.getMediaFilter(), result);
              var newCursor = cursorUtil.encodeMediaCursor(options, result.getId(), orderByValue);

              return new DefaultEdge<>(result, newCursor);
            })
        .toList();
  }

  private Object getOrderByValue(MediaFilter filter, Series series) {
    return switch (filter.getSortBy()) {
      case TITLE -> series.getTitle();
      case ADDED -> series.getCreatedOn();
    };
  }

  private Connection<? extends BaseCollectable<?>> usingCursorGetSeriesAsConnection(
      MediaPaginationOptions options) {
    var seriesList = seriesRepository.seekWithFilter(options);
    var edges = mapItemsToEdges(seriesList, options);

    return relayPaginationService.buildConnection(
        edges, options.getPaginationOptions(), options.getCursorId());
  }

  private void validateDecodedCursorAgainstFilter(
      MediaPaginationOptions decodedOptions, MediaFilter filter) {
    var previousFilter = decodedOptions.getMediaFilter();

    relayPaginationService.validateCursorField(
        "sortBy", previousFilter.getSortBy(), filter.getSortBy());
    relayPaginationService.validateCursorField(
        "sortDirection", previousFilter.getSortDirection(), filter.getSortDirection());
    relayPaginationService.validateCursorField(
        "libraryId", previousFilter.getLibraryId(), filter.getLibraryId());
  }
}
