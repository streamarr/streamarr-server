package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;

public interface MetadataProvider<T> {

  MetadataSearchOutcome search(VideoFileParserResult parserResult);

  MetadataFetchOutcome<MetadataResult<T>> getMetadata(
      RemoteSearchResult remoteSearchResult, Library library);

  ExternalAgentStrategy getAgentStrategy();
}
