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

  @Transactional
  public Series saveSeriesWithMediaFile(Series series, MediaFile mediaFile) {
    var savedSeries = seriesRepository.saveAndFlush(series);

    savedSeries.addFile(mediaFile);

    return seriesRepository.save(savedSeries);
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
