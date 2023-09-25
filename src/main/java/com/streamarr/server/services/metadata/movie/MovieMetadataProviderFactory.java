package com.streamarr.server.services.metadata.movie;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.services.metadata.MetadataProvider;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class MovieMetadataProviderFactory {

    private final List<MetadataProvider<Movie>> movieProviders;

    private final Logger log;

    public Optional<RemoteSearchResult> search(Library library, VideoFileParserResult videoFileParserResult) {
        var optionalProvider = getProviderForLibrary(library);

        if (optionalProvider.isEmpty()) {
            log.error("No metadata provider found for {} library while searching for {}", library.getName(), videoFileParserResult.title());
            return Optional.empty();
        }

        var provider = optionalProvider.get();

        return provider.search(videoFileParserResult);
    }

    // TODO: Rename this method, it actually does more than just "getting metadata"
    public Optional<Movie> getMetadata(RemoteSearchResult remoteSearchResult, Library library) {
        var optionalProvider = getProviderForLibrary(library);

        if (optionalProvider.isEmpty()) {
            log.error("No metadata provider found for {} library while enriching {}", library.getName(), remoteSearchResult.title());
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
