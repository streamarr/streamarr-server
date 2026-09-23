package com.streamarr.server.services.metadata.series;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.exceptions.MissingMetadataProviderException;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.MetadataSearchOutcome;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.TemporarilyUnavailable;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeriesMetadataProviderResolver {

  private final List<SeriesMetadataProvider> seriesProviders;

  public MetadataSearchOutcome search(
      Library library, VideoFileParserResult videoFileParserResult) {
    var optionalProvider = getProviderForLibrary(library);

    if (optionalProvider.isEmpty()) {
      log.error(
          "No metadata provider found for {} library while searching for {}",
          library.getName(),
          videoFileParserResult.title());
      return new TemporarilyUnavailable(missingProvider(library));
    }

    return optionalProvider.get().search(videoFileParserResult);
  }

  public MetadataFetchOutcome<MetadataResult<Series>> getMetadata(
      RemoteSearchResult remoteSearchResult, Library library) {
    var optionalProvider = getProviderForLibrary(library);

    if (optionalProvider.isEmpty()) {
      log.error(
          "No metadata provider found for {} library while enriching {}",
          library.getName(),
          remoteSearchResult.title());
      return new MetadataFetchOutcome.Failed<>(missingProvider(library));
    }

    return optionalProvider.get().getMetadata(remoteSearchResult, library);
  }

  public MetadataFetchOutcome<SeasonDetails> getSeasonDetails(
      Library library, String seriesExternalId, int seasonNumber) {
    var optionalProvider = getProviderForLibrary(library);

    if (optionalProvider.isEmpty()) {
      log.error(
          "No metadata provider found for {} library while fetching season details",
          library.getName());
      return new MetadataFetchOutcome.Failed<>(missingProvider(library));
    }

    return optionalProvider.get().getSeasonDetails(library.getId(), seriesExternalId, seasonNumber);
  }

  public List<Integer> getAvailableSeasonNumbers(Library library, String seriesExternalId) {
    var optionalProvider = getProviderForLibrary(library);

    if (optionalProvider.isEmpty()) {
      log.error(
          "No metadata provider found for {} library while fetching available season numbers",
          library.getName());
      return List.of();
    }

    return optionalProvider.get().getAvailableSeasonNumbers(library.getId(), seriesExternalId);
  }

  public OptionalInt resolveSeasonNumber(
      Library library, String seriesExternalId, int parsedSeasonNumber) {
    var optionalProvider = getProviderForLibrary(library);

    if (optionalProvider.isEmpty()) {
      return OptionalInt.of(parsedSeasonNumber);
    }

    return optionalProvider
        .get()
        .resolveSeasonNumber(library.getId(), seriesExternalId, parsedSeasonNumber);
  }

  private static MissingMetadataProviderException missingProvider(Library library) {
    return new MissingMetadataProviderException(library.getExternalAgentStrategy());
  }

  private Optional<SeriesMetadataProvider> getProviderForLibrary(Library library) {
    return seriesProviders.stream()
        .filter(provider -> library.getExternalAgentStrategy().equals(provider.getAgentStrategy()))
        .findFirst();
  }
}
