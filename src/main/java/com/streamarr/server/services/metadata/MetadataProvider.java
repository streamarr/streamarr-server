package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;

import java.net.http.HttpClient;
import java.util.Optional;

public interface MetadataProvider<T> {

    Optional<RemoteSearchResult> search(VideoFileParserResult parserResult, HttpClient client);

    Optional<T> getMetadata(RemoteSearchResult remoteSearchResult, Library library);

    ExternalAgentStrategy getAgentStrategy();

}
