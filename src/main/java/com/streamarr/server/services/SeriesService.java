package com.streamarr.server.services;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;
import com.streamarr.server.graphql.cursor.PaginationOptions;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import graphql.relay.Connection;
import graphql.relay.DefaultEdge;
import graphql.relay.Edge;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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
  public Series createSeriesWithAssociations(Series series) {
    series.setCast(personService.getOrCreatePersons(series.getCast()));
    series.setDirectors(personService.getOrCreatePersons(series.getDirectors()));
    series.setGenres(genreService.getOrCreateGenres(series.getGenres()));
    series.setStudios(companyService.getOrCreateCompanies(series.getStudios()));

    var savedSeries = seriesRepository.saveAndFlush(series);

    publishImageEvent(savedSeries);

    return savedSeries;
  }

  private void publishImageEvent(Series series) {
    var sources = new ArrayList<ImageSource>();

    if (series.getPosterPath() != null) {
      sources.add(new TmdbImageSource(ImageType.POSTER, series.getPosterPath()));
    }
    if (series.getBackdropPath() != null) {
      sources.add(new TmdbImageSource(ImageType.BACKDROP, series.getBackdropPath()));
    }
    if (series.getLogoPath() != null) {
      sources.add(new TmdbImageSource(ImageType.LOGO, series.getLogoPath()));
    }

    if (!sources.isEmpty()) {
      eventPublisher.publishEvent(
          new MetadataEnrichedEvent(series.getId(), ImageEntityType.SERIES, sources));
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
        .map(
            result -> {
              var orderByValue = getOrderByValue(options.getMediaFilter(), result);
              var newCursor = cursorUtil.encodeMediaCursor(options, result.getId(), orderByValue);

              return new DefaultEdge<>(result, newCursor);
            })
        .collect(Collectors.toList());
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
