package com.streamarr.server.services.library;

import com.streamarr.server.domain.Library;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DateBasedEpisodeResolver {

  private final SeriesMetadataProviderResolver seriesMetadataProviderResolver;

  public record DateResolution(int seasonNumber, int episodeNumber) {}

  public Optional<DateResolution> resolve(Library library, String externalId, LocalDate date) {
    var result = resolveForYear(library, externalId, date, date.getYear());

    if (result.isPresent()) {
      return result;
    }

    return resolveForYear(library, externalId, date, date.getYear() - 1);
  }

  private Optional<DateResolution> resolveForYear(
      Library library, String externalId, LocalDate date, int year) {
    var resolvedSeason =
        seriesMetadataProviderResolver.resolveSeasonNumber(library, externalId, year);

    if (resolvedSeason.isEmpty()) {
      return Optional.empty();
    }

    var seasonNumber = resolvedSeason.getAsInt();
    var seasonDetails =
        seriesMetadataProviderResolver.getSeasonDetails(library, externalId, seasonNumber);

    if (seasonDetails.isEmpty()) {
      return Optional.empty();
    }

    return seasonDetails.get().episodes().stream()
        .filter(ep -> date.equals(ep.airDate()))
        .findFirst()
        .map(ep -> new DateResolution(seasonNumber, ep.episodeNumber()));
  }
}
