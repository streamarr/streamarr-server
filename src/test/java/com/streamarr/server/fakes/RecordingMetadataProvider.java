package com.streamarr.server.fakes;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.MetadataSearchOutcome;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.Found;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.NotFound;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public class RecordingMetadataProvider<T> implements MetadataProvider<T> {

  private final Map<VideoFileParserResult, RemoteSearchResult> searchResults = new HashMap<>();
  private final List<VideoFileParserResult> searchRequests = new ArrayList<>();
  private final Map<String, Supplier<MetadataResult<T>>> metadata = new HashMap<>();

  public void willReturnMetadataFor(String externalId, Supplier<MetadataResult<T>> result) {
    metadata.put(externalId, result);
  }

  public RecordingMetadataProvider<T> willReturnSearchResultFor(
      VideoFileParserResult request, RemoteSearchResult result) {
    searchResults.put(request, result);
    return this;
  }

  public List<VideoFileParserResult> searchRequests() {
    return List.copyOf(searchRequests);
  }

  @Override
  public MetadataSearchOutcome search(VideoFileParserResult parserResult) {
    searchRequests.add(parserResult);
    return Optional.ofNullable(searchResults.get(parserResult))
        .<MetadataSearchOutcome>map(Found::new)
        .orElseGet(NotFound::new);
  }

  @Override
  public MetadataFetchOutcome<MetadataResult<T>> getMetadata(
      RemoteSearchResult remoteSearchResult, Library library) {
    return Optional.ofNullable(metadata.get(remoteSearchResult.externalId()))
        .<MetadataFetchOutcome<MetadataResult<T>>>map(
            result -> new MetadataFetchOutcome.Found<>(result.get()))
        .orElseGet(MetadataFetchOutcome.NotFound::new);
  }

  @Override
  public ExternalAgentStrategy getAgentStrategy() {
    return ExternalAgentStrategy.TMDB;
  }
}
