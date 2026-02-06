package com.streamarr.server.services;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.repositories.media.SeriesRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

  @Transactional(readOnly = true)
  public Optional<Series> findByTmdbId(String tmdbId) {
    return seriesRepository.findByTmdbId(tmdbId);
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
    seriesRepository.deleteAll(seriesList);
  }

  @Transactional
  public void deleteSeriesById(UUID seriesId) {
    seriesRepository.deleteById(seriesId);
  }
}
