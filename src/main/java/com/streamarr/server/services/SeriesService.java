package com.streamarr.server.services;

import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;
import com.streamarr.server.graphql.cursor.PaginationOptions;
import com.streamarr.server.repositories.media.SeriesRepository;
import graphql.relay.Connection;
import graphql.relay.DefaultEdge;
import graphql.relay.Edge;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeriesService {

  private final SeriesRepository seriesRepository;
  private final PersonService personService;
  private final GenreService genreService;
  private final CompanyService companyService;
  private final CursorUtil cursorUtil;
  private final RelayPaginationService relayPaginationService;

  @Transactional
  public Series saveSeriesWithMediaFile(Series series, MediaFile mediaFile) {
    var savedSeries = seriesRepository.saveAndFlush(series);

    savedSeries.addFile(mediaFile);

    return seriesRepository.save(savedSeries);
  }

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
    return seriesRepository.saveAndFlush(series);
  }

  @Transactional
  public Optional<Series> addMediaFileToSeriesByTmdbId(String tmdbId, MediaFile mediaFile) {
    var series = seriesRepository.findByTmdbId(tmdbId);

    if (series.isEmpty()) {
      log.debug("No series found with TMDB ID: {}", tmdbId);
      return Optional.empty();
    }

    series.get().addFile(mediaFile);
    return Optional.of(seriesRepository.saveAndFlush(series.get()));
  }

  @Transactional
  public void deleteByLibraryId(UUID libraryId) {
    var seriesList = seriesRepository.findByLibrary_Id(libraryId);
    if (seriesList.isEmpty()) {
      return;
    }
    seriesRepository.deleteAll(seriesList);
  }

  @Transactional
  public void deleteSeriesById(UUID seriesId) {
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

  private List<Edge<? extends BaseAuditableEntity<?>>> mapItemsToEdges(
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
