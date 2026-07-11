package com.streamarr.server.services.metadata.movie;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.MetadataResult;
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
public class MovieMetadataProviderResolver {

  private final List<MetadataProvider<Movie>> movieProviders;

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

    var provider = optionalProvider.get();

    return provider.search(videoFileParserResult);
  }

  public Optional<MetadataResult<Movie>> getMetadata(
      RemoteSearchResult remoteSearchResult, Library library) {
    var optionalProvider = getProviderForLibrary(library);

    if (optionalProvider.isEmpty()) {
      log.error(
          "No metadata provider found for {} library while enriching {}",
          library.getName(),
          remoteSearchResult.title());
      return Optional.empty();
    }

    var provider = optionalProvider.get();

    return provider.getMetadata(remoteSearchResult, library);
  }

  private Optional<MetadataProvider<Movie>> getProviderForLibrary(Library library) {
    return movieProviders.stream()
        .filter(provider -> library.getExternalAgentStrategy().equals(provider.getAgentStrategy()))
        .findFirst();
  }
}
