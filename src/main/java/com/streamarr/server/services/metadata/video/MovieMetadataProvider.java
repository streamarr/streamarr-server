package com.streamarr.server.services.metadata.video;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.services.parsers.video.VideoFileMetadata;

import java.net.http.HttpClient;
import java.util.Optional;

public interface MovieMetadataProvider {

    Optional<String> searchForMovie(VideoFileMetadata videoInformation, HttpClient client);

    Optional<Movie> buildEnrichedMovie(Library library, String externalId);
    
}
