package com.streamarr.server.fakes;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
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

public class RecordingMetadataProvider<T> implements MetadataProvider<T> {

  private final Map<VideoFileParserResult, RemoteSearchResult> searchResults = new HashMap<>();
  private final List<VideoFileParserResult> searchRequests = new ArrayList<>();

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
  public Optional<MetadataResult<T>> getMetadata(
      RemoteSearchResult remoteSearchResult, Library library) {
    return Optional.empty();
  }

  @Override
  public ExternalAgentStrategy getAgentStrategy() {
    return ExternalAgentStrategy.TMDB;
  }
}
