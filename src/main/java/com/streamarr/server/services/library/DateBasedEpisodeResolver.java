package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.series.SeasonDetails;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DateBasedEpisodeResolver {

  private final SeriesMetadataProviderResolver seriesMetadataProviderResolver;

  public record DateResolution(int seasonNumber, int episodeNumber) {}

  /**
   * Finds the episode that aired on the date in that year's season, or else the previous year's. A
   * provider failure is returned as is rather than read as a date with no episode.
   */
  public MetadataFetchOutcome<DateResolution> resolve(
      Library library, String externalId, LocalDate date) {
    var result = resolveForYear(library, externalId, date, date.getYear());

    if (!(result instanceof MetadataFetchOutcome.NotFound<DateResolution>)) {
      return result;
    }

    return resolveForYear(library, externalId, date, date.getYear() - 1);
  }

  private MetadataFetchOutcome<DateResolution> resolveForYear(
      Library library, String externalId, LocalDate date, int year) {
    return seriesMetadataProviderResolver
        .resolveSeasonNumber(library, externalId, year)
        .flatMap(
            seasonNumber ->
                seriesMetadataProviderResolver
                    .getSeasonDetails(library, externalId, seasonNumber)
                    .flatMap(seasonDetails -> episodeAiredOn(date, seasonNumber, seasonDetails)));
  }

  private static MetadataFetchOutcome<DateResolution> episodeAiredOn(
      LocalDate date, int seasonNumber, SeasonDetails seasonDetails) {
    return seasonDetails.episodes().stream()
        .filter(episode -> date.equals(episode.airDate()))
        .findFirst()
        .<MetadataFetchOutcome<DateResolution>>map(
            episode ->
                new MetadataFetchOutcome.Found<>(
                    new DateResolution(seasonNumber, episode.episodeNumber())))
        .orElseGet(MetadataFetchOutcome.NotFound::new);
  }
}
