package com.streamarr.server.services.metadata.series;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeriesMetadataProviderResolver {

  private final List<MetadataProvider<Series>> seriesProviders;

  public Optional<RemoteSearchResult> search(
      Library library, VideoFileParserResult videoFileParserResult) {
    var optionalProvider = getProviderForLibrary(library);

    if (optionalProvider.isEmpty()) {
      log.error(
          "No metadata provider found for {} library while searching for {}",
          library.getName(),
          videoFileParserResult.title());
      return Optional.empty();
    }

    return optionalProvider.get().search(videoFileParserResult);
  }

  public Optional<Series> getMetadata(RemoteSearchResult remoteSearchResult, Library library) {
    var optionalProvider = getProviderForLibrary(library);

    if (optionalProvider.isEmpty()) {
      log.error(
          "No metadata provider found for {} library while enriching {}",
          library.getName(),
          remoteSearchResult.title());
      return Optional.empty();
    }

    return optionalProvider.get().getMetadata(remoteSearchResult, library);
  }

  private Optional<MetadataProvider<Series>> getProviderForLibrary(Library library) {
    return seriesProviders.stream()
        .filter(provider -> library.getExternalAgentStrategy().equals(provider.getAgentStrategy()))
        .findFirst();
  }
}
