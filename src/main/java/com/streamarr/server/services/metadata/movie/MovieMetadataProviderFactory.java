package com.streamarr.server.services.metadata.movie;

import com.streamarr.server.domain.Library;
import com.streamarr.server.jooq.generated.tables.Movie;
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

    public Optional<RemoteSearchResult> searchForMovie(Library library, VideoFileParserResult videoFileParserResult) {
        var optionalProvider = getProviderForLibrary(library);

        if (optionalProvider.isEmpty()) {
            log.error("No search provider found for {} library", library.getName());
            return Optional.empty();
        }

        var provider = optionalProvider.get();

        return provider.search(videoFileParserResult);
    }

    private Optional<MetadataProvider<Movie>> getProviderForLibrary(Library library) {
        return movieProviders.stream()
            .filter(provider -> library.getExternalAgentStrategy().equals(provider.getAgentStrategy()))
            .findFirst();
    }
}
