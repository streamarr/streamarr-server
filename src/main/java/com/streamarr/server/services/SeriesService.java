package com.streamarr.server.services;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.repositories.media.SeriesRepository;
import java.util.Optional;
import java.util.UUID;
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
}
